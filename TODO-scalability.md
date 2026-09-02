# LogLens Scalability TODO — 15+ Services × 1.5-2 GB Each

> **Status: READY TO IMPLEMENT** — Load tested on 2026-03-30. Real numbers below.
> Target: Scale from 3 services (~500 MB each) to 15+ services (1.5-2 GB each).
> Process: Follow `STRATEGY.md` — Plan → Code → Test → Fix → Review → Sign-off per priority.

## Load Test Results (2026-03-30) — Baseline

**Environment**: 22 CPUs, 32 GB RAM, Java 21, -Xmx4g, 13 services × 2.5 GB (31 GB total)
**Result**: 36/36 tests PASSED. See `STRATEGY.md` for full per-test breakdown.

| Metric | Measured Value | Concern Level |
|---|---|---|
| Peak heap before GC | **2,718 MB** (of 4 GB) | CRITICAL — 68% heap, one concurrent user = OOM |
| Humongous allocations | **685** | HIGH — G1 struggling with large rawLine Strings |
| Full GC events | **0** | OK — no stop-the-world yet |
| Slowest test (T27: complex query) | **11.4s** | HIGH — 1.3M entries in Pass 2 |
| Fastest test (T6: binary+traceId) | **0.5s** | OK — binary search works great |
| Average test time | **4.5s** | MODERATE |
| GC time | 10.5s / 180s (5.8%) | MODERATE |

## Current vs Target

| Metric | Measured (13 services) | Target (15+ services) |
|---|---|---|
| Total log data | 31 GB (13 × 2.5 GB) | 22-30 GB (15 × 1.5-2 GB) |
| Lines scanned per search | 1.3M (13 × 100K) | 1.5M+ (15 × 100K) |
| LogEntry objects in memory | Up to 1.3M | Up to 1.5M |
| Peak RAM per search | **2.7 GB measured** | ~3+ GB estimated |
| Threads needed (parallel) | 13 | 15+ |
| Available threads (commonPool) | 21 (22 cores - 1) | **3 on 4-core prod server** |

---

## P0 — Dedicated IO Thread Pool

### Problem

`CompletableFuture.supplyAsync()` in `LogSearchService.java` line 188 uses no custom executor, so it runs on `ForkJoinPool.commonPool()` which has `CPU cores - 1` threads (typically 3 on a 4-core server).

With 15 services, execution becomes batched: 3 services scan → wait → next 3 → wait → next 3 → wait → next 3 → wait → last 3. That's 5 sequential rounds instead of 15 parallel scans. Wall time multiplied by ~5x.

This pool is also shared with all other `CompletableFuture` and `parallelStream()` usage in the JVM (Spring internals, other libraries). Any contention from Spring's async tasks further degrades scan performance.

### Proposed Solution — Resource-Aware Thread Pool

**Not** blindly `cores × 2` or `serviceCount` threads. Instead, use 50-60% of available cores:

```java
// Formula: min(serviceCount, max(2, availableCores × 0.6))
// 4-core prod:  min(15, max(2, 2.4))  = 3 threads  (leaves 1.4 cores for OS + Spring)
// 8-core prod:  min(15, max(2, 4.8))  = 5 threads  (leaves 3.2 cores)
// 22-core dev:  min(15, max(2, 13.2)) = 13 threads (leaves 8.8 cores)
```

Additionally bounded by memory:
```java
long maxMemMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
int memoryBound = (int)(maxMemMB / 150);  // ~150 MB per scanning thread worst case
poolSize = Math.min(poolSize, memoryBound);
```

```java
// In LogSearchService.java
private final ExecutorService scanPool;

public LogSearchService(LogLensConfig config, ...) {
    int cores = Runtime.getRuntime().availableProcessors();
    int serviceCount = config.getServices().size();
    int cpuBound = Math.max(2, (int)(cores * 0.6));
    long maxMemMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
    int memBound = (int)(maxMemMB / 150);
    int poolSize = Math.min(serviceCount, Math.min(cpuBound, memBound));
    log.info("Scan thread pool: {} threads (cores={}, services={}, memMB={})", poolSize, cores, serviceCount, maxMemMB);
    this.scanPool = Executors.newFixedThreadPool(poolSize, ...);
}
```

Then pass to supplyAsync:
```java
CompletableFuture.supplyAsync(() -> { ... }, scanPool)
```

### Configuration

```yaml
# application.yml
loglens:
  scan-pool-size: 0  # 0 = auto (resource-aware), or explicit override
```

### Files to Change

- `LogSearchService.java` — add ExecutorService field, pass to supplyAsync, add @PreDestroy shutdown
- `LogLensConfig.java` — add `scanPoolSize` property (optional)
- `application.yml` — add `loglens.scan-pool-size` (optional)

### Impact

- Wall time: ~5x faster for 15 services (all scan in parallel instead of 5 batches)
- Memory: no change (same number of scans, just concurrent instead of sequential)
- Effort: **Small** — one new field, one config property, one parameter change

### Open Questions

- [ ] What CPU count does the target server have? (4-core? 8-core? 16-core?)
- [ ] How much JVM heap will be allocated? (-Xmx flag)
- [ ] Should pool size be configurable via YAML, or auto-computed from cores?
- [ ] Should we add a @PreDestroy hook to shutdown the pool gracefully?

---

## P0 — Per-Service Offsets (Pagination Fix)

### Problem

Current pagination uses a single `Long nextOffset` shared across all services. This byte position belongs to ONE service's file. When "Load More" sends it back, all 15 services receive a byte offset that's only meaningful for the one service it came from.

Example with 3 services:
```
Service A (700 MB file): nextOffset = 45,238,912  ← came from here
Service B (16 MB file):  receives 45,238,912      ← file is only 16 MB, offset is past EOF
Service C (776 MB file): receives 45,238,912      ← random position in C's file, wrong data
```

From page 2 onward, only the "owning" service returns correct results. All other services return garbage, empty results, or crash on out-of-bounds reads.

### Proposed Solution

Replace single offset with per-service offset map:

**SearchRequest changes:**
```java
// Current:
private Long fileOffset;

// New: add alongside existing (keep fileOffset for backward compat)
private String searchId;
private Map<String, Long> serviceOffsets;  // key = service name, value = byte position
```

**SearchResult changes:**
```java
// Current:
public record SearchResult(..., Long nextOffset, ...) {}

// New:
public record SearchResult(..., Map<String, Long> nextOffsets, ...) {}
```

**Backend logic (LogSearchService.search()):**
After scanning each service, capture that service's last entry's fileOffset into the map:
```java
Map<String, Long> nextOffsets = new HashMap<>();
// After scanning service "routing", if its last entry has fileOffset=45238912:
nextOffsets.put("routing", 45238912L);
// After scanning service "print", if its last entry has fileOffset=8291234:
nextOffsets.put("print", 8291234L);
```

**Frontend (index.html loadMore()):**
```javascript
// Current:
body.fileOffset = lastNextOffset;  // single number

// New:
body.serviceOffsets = lastNextOffsets;  // { "routing": 45238912, "print": 8291234, ... }
```

### Files to Change

- `SearchRequest.java` — add `searchId`, `Map<String, Long> serviceOffsets`
- `LogSearchService.java` — build per-service offset map after parallel scan, pass per-service offset to each scan call
- `SearchResult` record — replace `Long nextOffset` with `Map<String, Long> nextOffsets`
- `index.html` — `loadMore()` sends `serviceOffsets` map, stores `lastNextOffsets` (object instead of number)

### Backward Compatibility

Keep the old `fileOffset` field in SearchRequest. If `serviceOffsets` is null/empty, fall back to `fileOffset` for single-service searches. This way existing API consumers don't break.

### Impact

- Correctness: broken pagination → working pagination for multi-service
- Memory: no change
- Effort: **Medium** — touches 4 files, requires frontend JS changes

### Open Questions

- [ ] Should we keep backward compat with the old `fileOffset` field, or clean break?
- [ ] Should `searchId` be introduced now (for future cache) or deferred?
- [ ] Per-service offset tracking: should it track by service name or by file path? (service name is simpler, file path handles edge case of two services sharing a log file)

---

## P1 — Streaming Merge with Bounded Heap

### Problem

Currently, ALL matching entries from ALL services are collected into one `ArrayList`, then sorted, then subListed to `limit` (500). With 15 services × 100K entries each (worst case, no filters), that's **1.5 million LogEntry objects** in memory simultaneously.

Each LogEntry holds `rawLine` (entire log block including stack traces, never truncated). Average ~1 KB, but ERROR entries with stack traces can be 3-5 KB. Worst case: **1.5-4.5 GB** of heap for one search.

Key code (LogSearchService.java lines 210-243):
```java
List<LogEntry> results = new ArrayList<>();
for (CompletableFuture<List<LogEntry>> f : futures) {
    results.addAll(f.get());                          // ← all 1.5M entries in memory
}
results.sort(Comparator.comparing(LogEntry::getTimestamp, tsComp));  // ← sort 1.5M in-place
// ... query filter on 1.5M ...
List<LogEntry> page = new ArrayList<>(results.subList(0, limit));    // ← copy first 500
```

The full 1.5M list stays in memory until the method returns and GC collects it.

### Proposed Solution: K-Way Merge with Bounded Priority Queue

Since each service returns entries already sorted by timestamp (scan order), we can merge them efficiently using a min/max heap:

```java
// Priority queue of size = limit (500)
// For DESC: max-heap (keep newest 500)
// For ASC: min-heap (keep oldest 500)

PriorityQueue<LogEntry> topN = new PriorityQueue<>(limit, comparator);

for (CompletableFuture<List<LogEntry>> f : futures) {
    for (LogEntry e : f.get()) {
        if (topN.size() < limit) {
            topN.add(e);
        } else if (comparator.compare(e, topN.peek()) < 0) {
            topN.poll();
            topN.add(e);
        }
        // else: discard — worse than all current top-N entries
    }
}
```

Peak memory: **500 LogEntry objects** instead of 1.5M. Over 3,000x reduction.

### Complication: Query Filter (Pass 2)

The priority queue approach works great if there's NO query filter. But if a query filter exists, we can't pre-filter during merge because entries that pass structural filters might fail the query filter.

Options:
1. **Apply query during merge**: Move QueryEngine evaluation into the merge loop. This means each entry is checked against the query before entering the heap. Requires tokenizing the query once and reusing it.
2. **Two-phase bounded merge**: First collect all structural matches (unbounded), apply query, then bounded pagination. Same memory issue.
3. **Oversample + filter**: Collect `limit × 10` entries into heap, filter by query, take top `limit`. Approximate but bounded memory.

### Files to Change

- `LogSearchService.java` — replace ArrayList merge+sort with PriorityQueue k-way merge
- `QueryEngine.java` — possibly: pre-compile query once, reuse for all entries (currently re-tokenizes per entry)

### Impact

- Memory per search: 1.5-4.5 GB → ~500 KB (for 500 entries) or ~50 MB (for 50K safeCap)
- CPU: slightly higher per-entry overhead (heap insert vs array append), but sort is eliminated
- Effort: **Large** — rethinking the merge+sort+filter pipeline, handling query filter interaction

### Open Questions

- [ ] Should the query filter move into the merge loop (pre-merge filtering) or stay as post-merge?
- [ ] If query moves to pre-merge, can we pre-tokenize the query once and reuse?
- [ ] What about `filteredByStructured` count? Currently it's `results.size()` before query filter. With streaming merge, we'd need a separate counter.
- [ ] Does the bounded heap approach work with "Load More"? Each page needs to continue from where the last left off — but the heap only keeps top-N, not the continuation position.
- [ ] Should safeCap (50K) be reduced for 15+ services to prevent memory issues?

---

## P1 — In-Memory Search Cache

### Problem

Every "Load More" request re-scans 100K lines per service from disk. First page shows 500 entries from (say) 14,900 matches. The other 14,400 entries are **discarded**. Next "Load More" re-scans the same file from a byte offset, re-parses 100K lines, finds another set of matches, returns 500 more. The previously found 14,400 entries were wasted work.

With 15 services × 100K lines × multiple Load More clicks, disk I/O adds up:
```
Page 1: 15 services × 18 MB = 270 MB disk I/O
Page 2: 15 services × 18 MB = 270 MB disk I/O  (most of it re-reading same area)
Page 3: 15 services × 18 MB = 270 MB disk I/O
Total for 3 pages: ~810 MB disk I/O
```

### Proposed Solution

First search scans and caches ALL matching entries (per searchId). Subsequent "Load More" pages served from cache with zero disk I/O. Re-scan only when cache is depleted.

```java
// In LogSearchService.java
private final ConcurrentHashMap<String, SearchCache> cacheStore = new ConcurrentHashMap<>();

class SearchCache {
    String searchId;
    List<LogEntry> entries;          // all matching entries (sorted, query-filtered)
    int cursor;                       // position of next page start
    Map<String, Long> serviceOffsets; // per-service byte positions for re-scan
    Instant createdAt;                // for TTL eviction
}
```

Flow:
```
Search (new):
  → Generate searchId (UUID)
  → Scan all services → collect + sort + query-filter → cache ALL results
  → Return first 500 + searchId

Load More (searchId exists):
  → Look up cache → serve entries[cursor..cursor+500]
  → Update cursor
  → If cache depleted (< 1000 entries remaining): re-scan from serviceOffsets, append to cache

Load More (searchId missing/expired):
  → Fall back to current behavior (re-scan from offset)
```

### Memory Budget

```
Per cached search: 14,900 entries × ~1 KB = ~15 MB
With 5 concurrent searches: ~75 MB
With stack traces: 3-5x → ~225-375 MB
```

### Eviction Strategy

- **TTL**: 10 minutes — searches older than 10 min are evicted automatically
- **Max concurrent caches**: 5-10 — oldest evicted when limit reached (LRU)
- **New search**: always creates new cache; old cache for same user expires via TTL
- **Scheduled cleanup**: `@Scheduled(fixedRate = 60000)` to purge expired caches every minute

### Configuration

```yaml
loglens:
  cache:
    max-searches: 5       # max concurrent cached searches
    ttl-minutes: 10        # cache expiry
    max-entries: 50000     # max entries per cache (memory safety)
```

### Files to Change

- `LogSearchService.java` — SearchCache class, ConcurrentHashMap, cache lookup/store/eviction logic
- `SearchRequest.java` — add `searchId` field
- `SearchResult` record — add `searchId`, `totalCached` fields
- `LogLensConfig.java` — add cache config properties
- `application.yml` — add `loglens.cache.*` properties
- `index.html` — store `searchId` from first response, send it back on "Load More"

### Impact

- Disk I/O per Load More: ~270 MB → 0 (served from cache)
- Memory: +15-375 MB per cached search (depending on match count and stack traces)
- Effort: **Large** — cache lifecycle, eviction, concurrency, searchId management

### Open Questions

- [ ] Cache key: `searchId` (UUID) — how to handle browser refresh? (new search = new UUID = new cache, old one expires via TTL — seems fine)
- [ ] Tab duplication: user opens same search in two tabs → two separate caches. Acceptable?
- [ ] What if log file grows between page requests? Stale cache shows old data. Accept stale data within TTL window, or add file-modified-time check?
- [ ] Memory budget: with 15 services and broad filters, one search could cache 100K+ entries (~100 MB+). Should we cap entries per cache?
- [ ] Should cache store raw LogEntry objects or just the JSON-ready subset (without rawLine) to save memory?
- [ ] Thread safety: ConcurrentHashMap for cache store, but what about concurrent reads/writes to the same cache's cursor? Need AtomicInteger or synchronized block.
- [ ] Should "new search" (different filters) automatically invalidate the old cache, or let TTL handle it?

---

## P2 — Frontend DOM Virtualization

### Problem

`index.html` renders **every entry as full DOM nodes** — no virtualization, no windowing. The `lastEntries` JS array grows with every "Load More" click, and `renderEntries()` (line 948) regenerates the entire HTML via innerHTML.

Each entry generates ~20-30 DOM elements (outer div, row div, message span, field tags, detail div, etc.).

```
500 entries  = ~15,000 DOM nodes   → fine
2,000 entries = ~60,000 DOM nodes  → noticeable lag
5,000 entries = ~150,000 DOM nodes → slow scrolling, high memory
50,000 entries = ~1.5M DOM nodes   → browser tab crashes
```

With 15 services, even moderate searches accumulate entries quickly through Load More.

### Proposed Solution: Virtual Scrolling (Vanilla JS)

Only render the ~50-100 entries visible in the viewport. As the user scrolls, recycle/replace DOM nodes.

```javascript
const VISIBLE_BUFFER = 80;       // entries to keep in DOM (visible + buffer above/below)
const ROW_HEIGHT = 60;           // estimated height per collapsed entry (px)

function virtualRender() {
    const scrollTop = container.scrollTop;
    const startIdx = Math.floor(scrollTop / ROW_HEIGHT);
    const renderFrom = Math.max(0, startIdx - 20);     // 20-entry buffer above
    const renderTo = Math.min(lastEntries.length, startIdx + VISIBLE_BUFFER);

    // Set total container height (for scrollbar sizing)
    spacer.style.height = (lastEntries.length * ROW_HEIGHT) + 'px';

    // Render only visible slice
    const visibleEntries = lastEntries.slice(renderFrom, renderTo);
    content.style.top = (renderFrom * ROW_HEIGHT) + 'px';
    content.innerHTML = visibleEntries.map(e => entryHtml(e, cols, hlTerms)).join('');
}

container.addEventListener('scroll', virtualRender);
```

### Complication: Variable Row Heights

Log entries with expanded stack traces have different heights. Options:
1. **Fixed height estimate**: Simple, fast, but scrollbar position slightly inaccurate
2. **Measured heights**: Cache measured height per entry after first render. More accurate but complex.
3. **Hybrid**: Use fixed height for collapsed entries, re-measure when expanded

### Files to Change

- `index.html` — replace `renderEntries()` with virtual scroller, add scroll listener, modify entry expand/collapse to update virtual heights

### Impact

- Browser can handle any entry count (50K+) without DOM collapse
- Memory: JS `lastEntries` array still grows, but DOM stays at ~80 entries max
- Effort: **Medium** — custom scroll handler, absolute positioning, variable height handling

### Open Questions

- [ ] Should Load More still exist, or switch to infinite scroll (auto-trigger when near bottom)?
- [ ] How to handle row height for expanded entries (stack trace visible)?
- [ ] Should keyword highlighting still run on all entries or only visible ones?
- [ ] Browser testing: how does this perform in Chrome vs Firefox vs Edge?

---

## What Works Fine at 15+ Services (No Changes Needed)

These components scale without modification:

- **Strategy selection** (`selectStrategy()`) — per-file, stateless, scales to any service count
- **Binary search** — `log2(2 GB / 64 KB)` ≈ 15 iterations. Works great on 2 GB files
- **Two-pass filtering** — structural + query pipeline is per-entry, no cross-service state
- **LogParserService** — stateless, compiled regex, O(1) isNewEntry(). Handles any file size
- **QueryEngine** — in-memory boolean evaluation, no scaling concern (runs after scan)
- **Scan I/O** — readFully(64KB) and BufferedReader(8KB) scale linearly with file size
- **Configuration model** — adding services to application.yml is just more list entries
- **Binary search safety margin** (1 MB) — proportionally fine for 2 GB files

---

## Implementation Order

```
P0a: Dedicated Thread Pool  ──► P0b: Per-Service Offsets  ──► P1b: In-Memory Cache
       (DONE)                        (DONE)                       (DONE)
                                                                       │
                                                              Fixes pagination skip.
                                                              Load More from cache = 0ms.
                                                              Query re-filter from cache.
                                                                       │
                                                              P1a: Streaming Merge
                                                              (DONE — bounded PQ,
                                                               humongous 685→138)
                                                                       │
                                                              P2: Frontend Virtualization
                                                              (medium, UI fix)
                                                                       │
                                                              Fully scalable end-to-end
```

---

## Decision Log

| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-03-26 | Created this TODO | User wants to scale to 15+ services × 1.5-2 GB |
| 2026-03-26 | Deferred cache + per-service offsets | "Not now, we'll implement later. Not straightforward." |
| 2026-03-30 | Load tested 13 services × 2.5 GB | 36/36 PASS. Peak heap 2.7 GB. Query bottleneck 11.4s. |
| 2026-03-30 | Thread pool: resource-aware sizing | User: "shouldn't it be 50-60% of remaining resources?" |
| 2026-03-30 | Cache: pure in-memory (ConcurrentHashMap) | No external deps. Fits single-JAR philosophy. |
| 2026-03-30 | Created STRATEGY.md | Formal dev lifecycle: Plan→Code→Test→Fix→Review per priority |
| 2026-03-30 | P0b DONE with known pagination skip | Offset plumbing works for single-service. Multi-service Load More skips entries — cache fixes this. |
| 2026-03-30 | Reprioritized P1b before P1a | Cache solves pagination correctly; bounded heap conflicts with cache (can't discard entries we need to cache). P1b first. |
