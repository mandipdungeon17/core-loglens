# LogLens - Project Context

> **Claude: start here.** This file is the single source of truth for understanding this project.
> After a session loss, reading this file + `git log --oneline -5` is enough to resume work.
> To sync after new changes: user says **"sync context"** → read diff, update this file.

## What This Is

core-loglens (LogLens) is a lightweight, browser-based log viewer for Honeywell WES microservices.
Reads log files directly from the filesystem — no ELK stack, no Kafka, no database.
Single executable Spring Boot JAR with a self-contained Thymeleaf + vanilla JS frontend.
Scales elastically from 3 to 15+ services — resource consumption proportional to actual service count.

## Tech Stack

- Java 21, Spring Boot 3.5.8, Gradle 8.14.2
- Thymeleaf (server-side HTML), Vanilla JS + CSS (no npm, no frontend framework)
- Lombok (@Data, @Builder, @Slf4j), Jackson with JavaTimeModule
- 2-space indentation for all Java files

## Project Structure

```
src/main/java/com/honeywell/loglens/
  LogLensApplication.java          # Spring Boot entry point
  config/
    LogLensConfig.java             # Binds loglens.* from application.yml (services, tailLines, maxScanLines, maxScanLinesLimit, scanPoolSize, showMetrics)
    JacksonConfig.java             # ObjectMapper with JavaTimeModule, ISO dates
  model/
    LogEntry.java                  # Parsed log record (14 fields + fileOffset for pagination)
    SearchRequest.java             # Incoming search params (multi-value traceIds/spanIds, searchId for cache, maxScanLines override)
    ServiceConfig.java             # Service definition (name, logFile path, color)
  controller/
    LogController.java             # REST: POST /search, POST /search/historical, GET /services, /services/{name}/files, /servertime, /metrics, /health, /export, POST /cache/clear
    UIController.java              # Serves index.html via Thymeleaf with services list + showMetrics injected
  service/
    LogSearchService.java          # THE CORE (~1440 lines) — strategy-based scanning, two-pass filtering, hybrid query, parallel scanning, two-layer cache, bounded merge, historical file search
    LogParserService.java          # Parses 5 log formats: ANSI colored, plain Log4j2, slim WES Log4j2, slim WES {:} separator, ISO-8601 JVM startup
    QueryEngine.java               # Recursive descent boolean query parser (AND/OR/NOT, field:value, quoted phrases)
src/main/resources/
  application.yml                  # Config: port 8090, context-path /loglens, services list, tailLines, maxScanLines, scanPoolSize, showMetrics
  templates/
    index.html                     # Entire single-page UI (~1904 lines: HTML + CSS + vanilla JS + virtual scroll + metrics bar + historical log viewer)
src/test/java/com/honeywell/loglens/
  controller/
    LogControllerTest.java         # 14 REST endpoint tests (@WebMvcTest)
  service/
    LogParserServiceTest.java      # 28 parser tests (5 formats, ANSI edge cases, {:} separator)
    LogSearchServiceTest.java      # 45 tests (cache, pagination, offsets, filters, file edge cases, hybrid query)
    MultiUserCacheIntegrationTest.java  # 6 concurrent access / session eviction tests
    QueryEngineTest.java           # 18 tests (AND/OR/NOT, fields, aliases, nesting)
```

## Architecture

### Two-Pass Filtering (with Hybrid Query Mode)

- Pass 1 (Structural): Runs DURING file scan. Checks level, traceId, spanId, userId, siteId, tenantId, logger, message, time range. Only matching entries survive.
- Pass 2 (Free-form Query): Runs AFTER merge + sort. Applies query bar expression (boolean logic, field:value). Narrows structural results further.
- **Hybrid Query Mode**: When scan depth > default (100K) AND query is present, query is applied BEFORE PQ merge/cap. Only query-matching entries occupy PQ slots, maximizing recall for rare matches in deep scans. `bakedQuery` field tracks this on `SearchCache`. Fingerprint includes query (`dq-`/`hdq-` prefix) so query change = rescan.
- Default scan depth preserves instant Layer 2 refilter (zero I/O on query change).
- UI shows both counts: "X query matches from Y filtered entries"

### Scan Strategy Pattern (LogSearchService)

- BACKWARD: RandomAccessFile reading 64KB chunks from end toward start. For presets, recent ranges, DESC.
- FORWARD: BufferedReader + FileInputStream with manual byte tracking for non-gz. BufferedReader via GZIPInputStream for .gz. For ASC sort, .gz files.
- BINARY_THEN_BACKWARD: Binary search for toTime via binarySearchPositionAfter() (~20 seeks), then backward scan. For historical DESC ranges (>3h ago).
- BINARY_THEN_FORWARD: Binary search for fromTime via binarySearchPosition(), then forward scan. For ASC + time filter.
- Strategy selected per-file per-request via selectStrategy() method.

### Resource-Aware Thread Pool

Dedicated ExecutorService with daemon threads (loglens-scan-N). Pool sizing: `min(serviceCount, min(cores*0.6, heapMB/150))`.
3 services = 3 threads max. 15 services on 8-core = 4 threads. Configurable via `scan-pool-size` (0=auto).

### Two-Layer In-Memory Cache

ConcurrentHashMap<String, SearchCache> cacheStore (max 5 concurrent caches):

1. **Layer 1 (Structural)**: Up to 100K entries from disk scan. Keyed by structural filter fingerprint. Tracks file sizes for freshness.
2. **Layer 2 (Query View)**: Derived from Layer 1 by applying query filter. Changes without disk I/O. Has cursor for Load More pagination.

Cache behavior: DESC reuses if file sizes unchanged. ASC always reuses. Query change = zero I/O (43-111x faster).
Load More = zero I/O (~0.014s). Sliding TTL 15 min. LRU eviction at capacity. ~200 MB max memory.
Deep scan + query: `bakedQuery` set on cache, query included in fingerprint (`dq-`/`hdq-` prefix), query change = rescan (new cache).

### Bounded PriorityQueue Merge

PQ capacity 100K entries. DESC keeps newest (min-heap, oldest at peek). ASC keeps oldest (reversed).
Progressive GC: `futures.set(i, null)` after each batch. PQ initial capacity 16384.

### Virtual Scroll (Frontend)

VS object renders ~80 visible DOM entries. Sentinel+content pattern. Float64Array positions for O(log n) binary search.
rAF-throttled at ~60fps. Expand/collapse by data index (survives recycling). Infinite scroll at 300px from bottom.

### Log Parsing (LogParserService)

Five formats: ANSI colored (ESC stripped), Plain Log4j2, Slim WES Log4j2 (no hostname/PID), Slim WES with {:} separator, ISO-8601 JVM startup.
isNewEntry() is O(1) check for YYYY-MM-DD pattern (handles multiple ANSI prefixes and T separator).

### QueryEngine

Recursive descent parser. Grammar: expr = term (AND|OR term)\* | term = NOT term | '(' expr ')' | atom
Supported fields: level, service, userId/user, siteId/site, tenantId/tenant, traceId/trace, spanId/span, logger/class, thread, message/msg
level = exact match; all others = substring match (case-insensitive). Malformed queries fall back to raw substring.

## REST API (all under /loglens context-path)

- POST /loglens/api/logs/search — Two-pass search with caching (body: SearchRequest, returns SearchResult)
- GET /loglens/api/logs/services — List services with file status
- GET /loglens/api/logs/services/{name}/files — List archived/historical log files for a service
- POST /loglens/api/logs/search/historical — Search a single historical/archived log file (params: serviceName, fileName; body: SearchRequest)
- GET /loglens/api/logs/servertime — Server local time for timezone sync
- GET /loglens/api/logs/metrics — Live resource metrics (CPU, memory, heap, GC, threads, uptime, cache stats)
- GET /loglens/api/logs/export — Download filtered logs as TXT or CSV (params: searchId, format)
- GET /loglens/api/logs/health — Health check
- POST /loglens/api/logs/cache/clear — Clear all in-memory caches + System.gc() hint

## Frontend (index.html)

Single-page app, vanilla JS. Key features:

- **Live / Historical mode toggle**: Tab bar at top of main area switches between live and historical log viewing
- **Historical log viewer**: Component + file dropdowns to browse archived/rotated log files (.log.1, .log.gz, etc.) per service
- **Per-tab state isolation**: Each mode (live/hist) saves and restores its own entries, searchId, chips, sort, preset, filter values
- Virtual scroll (VS object): Only ~80 DOM entries rendered, O(log n) scroll lookup, infinite scroll
- Structured filters sidebar with bidirectional chip sync
- Click-to-filter: clicking a field value creates a filter chip
- Dedicated expand/collapse toggle button (▶/▼) per log entry — text freely selectable without triggering expand
- Load More pagination via searchId (cache-based, instant)
- Export/Download: TXT (raw log) and CSV (structured columns) via streaming backend endpoint, all cached entries
- Server time sync: header clock shows server time, toServerISO() for manual inputs
- Live metrics bar: CPU, memory, heap, GC, threads, uptime, cache stats — 4s polling, color-coded thresholds, configurable via showMetrics
- Collapsible Tools section: Clear Cache, Health Check, Services Info
- AbortController to cancel stale fetch requests
- XSS prevention via esc() with quote escaping
- Key JS globals: lastEntries, lastSearchId, currentChips, serverDeltaMs, BASE, currentMode, modeState

## Resource Scaling (3 → 15 Services)

All resources scale elastically with actual service count:

- **Thread pool**: min(serviceCount, ...) — 3 svcs = 3 threads, not 15
- **Scan futures**: Only created for existing files — missing files = zero cost
- **Cache**: Bounded at 100K entries regardless of service count
- **PQ merge**: Bounded at 100K — never grows beyond that
- **Memory**: Idle ~80 MB regardless of configured services. 1 user ~160-250 MB. 5 users ~400-800 MB.
- **JVM sizing**: 3-5 svcs → 512 MB. 6-10 svcs → 1-2 GB. 11-15 svcs → 2-4 GB.

## Configuration

- application.yml: port 8090, context-path `/loglens`, tailLines 500, maxScanLines 100000, maxScanLinesLimit 10000000, scanPoolSize 0 (auto), showMetrics (true/false)
- 3 services configured (routing, print, momentumconnect), expandable to 15+
- Log file paths use ${ENV_VAR:default} syntax for environment variable overrides
- Version in gradle.properties: 2.1.2-SNAPSHOT

## Build & Run

- Build: ./gradlew clean bootJar → build/libs/loglens-2.1.2-SNAPSHOT-exec.jar
- Run: java -jar build/libs/loglens-2.1.2-SNAPSHOT-exec.jar
- Run (large): java -Xmx2g -jar build/libs/loglens-2.1.2-SNAPSHOT-exec.jar
- CI: GitHub Actions workflow reuses HON-IA/gh-wes-common shared workflow
- Publish: ./gradlew publish (SNAPSHOT → snapshot repo, release → stable repo)

## Coding Conventions

- 2-space indentation for ALL Java files
- Import order: java.\* first, then third-party (lombok, spring)
- No npm, no frontend framework, no build step for JS — everything vanilla in index.html
- Lombok everywhere: @Data, @Builder, @Slf4j
- Prefer records for immutable data (ResolvedFilters, SearchResult, Token)
- Helper methods: `nb(s)` = not blank, `clean(s)` = trim + nullify empties

## Branch Info

- Main branch: main
- Current dev branch: j21pr/logslens_v2

## Change Log

### v2.1.2-SNAPSHOT — Coverity/BlackDuck remediation (current)

**Static Analysis Fixes**

- CWE-117 (Log Injection): `sanitizeForLog()` strips CRLF from user-supplied strings in log statements (LogController, LogSearchService)
- CWE-476 (Null Pointer): Added null guards for `batch` in merge loop and `heap.peek()` in PQ eviction
- CWE-252 (Unchecked Return): Captured `transferTo()` return value with warning on 0 bytes
- JLM (Sync on ConcurrentHashMap): Dedicated `sessionsLock` and `decompressLock` objects replace `synchronized(concurrentMap)`
- FB.DM_GC (System.gc): Removed explicit `System.gc()` call from cache clear
- DLS (Dead Store): Removed unused variable assignments in tests and service
- SpotBugs (Untyped Matcher): `any()` → `any(SearchRequest.class)` in mock setups

**Testing (114 tests)**

- All 114 tests pass with 0 failures

### v2.1.1-SNAPSHOT — Audit cleanup + patch

**Security**

- XSS fix: added `jsEsc()` for JS string context escaping in all 8 click-to-filter onclick handlers (includes line terminator escaping)
- Error handling: POST `/search` returns generic message for 500, maps `IllegalArgumentException` to 400 — no internal detail leakage

**Core Engine (LogSearchService)**

- Hybrid query mode: when scan depth > default (100K), query is applied BEFORE PQ merge/cap for maximum recall on deep scans
  - `isDeepScan()` helper: `resolveMaxScan(req) > config.getMaxScanLines()`
  - `bakedQuery` field on `SearchCache`: non-null = query was pre-applied, `createSession()` skips re-filtering
  - Fingerprint includes query for deep scan (`dq-` prefix live, `hdq-` prefix historical), query change = cache miss = rescan
  - Default scan depth (100K) preserves instant Layer 2 refilter (zero behavioral change)
- User-selectable scan depth: `resolveMaxScan()` uses per-request override (capped by `maxScanLinesLimit`) or server default
- Fixed `binarySearchPosition()` returning EOF when `targetTime` is null — ASC + to-only filter now scans from beginning
- Fixed inconsistent `maxScanLines` counting: `scanForward`/`scanForwardGz` now count only entry-start lines (matching `scanBackward`)
- Fixed `lineNumber` accuracy in forward scans: separate `rawLines` counter tracks actual file lines (was using entry counter)
- Fixed ASC PriorityQueue eviction: null-timestamp entries now kept (not evicted first) via `nullsLast`
- Aligned ASC final sort with eviction comparator: both use `nullsLast` — null-timestamp entries appear at end, not beginning
- Fixed `scanForwardGz` missing `StandardCharsets.UTF_8` charset
- Simplified `selectStrategy()` — flat early-return structure, no redundant nested if-checks
- ASC + any time filter now uses `BINARY_THEN_FORWARD` (not just when `from` is set)
- DESC + any time filter now uses `BINARY_THEN_BACKWARD` (removed 3-hour threshold)

**Frontend (index.html)**

- Removed `toServerISO()` conversion — all timestamps are now consistently in server time (presets and manual input both pass-through, no double conversion)
- Human-readable server time tooltip: shows delta as `+5h 30m`, `+30s`, or `in sync`
- Hint text clarifies local-time fallback when server sync fails
- `VS.clear()` called before error innerHTML in both `doSearch`/`doHistSearch` (fixes stale DOM refs)
- Historical file dropdown repopulated on mode restore (fixes blank dropdown on tab switch back)
- `doSearch` now parses server error JSON (matching `doHistSearch` behavior)
- Console warning when service filter intersection produces empty array
- Removed dead code: `localInput()`, `lastReqBase`, duplicate fingerprint `.hashCode()` logs
- Scan Depth dropdown in toolbar (100K–10M): per-search scan depth override, sent as `maxScanLines` in request

**Configuration**

- `JacksonConfig` uses `Jackson2ObjectMapperBuilderCustomizer` with `modulesToInstall` (additive — preserves auto-configured modules, YAML `spring.jackson.*` properties effective)

**Parser (LogParserService)**

- Format E support: SLIM and FULL regex separators accept both `:` and `{:}` (brace separator found on certain MomentumConnect deployments)

**QueryEngine**

- Fixed `matches(null, query)` returning NPE — now returns `false`

**Testing (111 tests)**

- Added: hybrid query deep scan (6 tests: pre-PQ filter, Layer 2 preserved, query change rescan, no-query default, Load More, historical)
- Added: .gz file search, export data retrieval, multiline stack trace parsing, implicit juxtaposition, NOT with AND/OR
- Added: /search IllegalArgumentException→400 and Exception→500 error handling tests
- Fixed: null entry test updated from NPE assertion to `isFalse()`, removed duplicate test, unused import

### v2.0.0-SNAPSHOT — Major version

Summary of all changes since v1.0.0:

**Core Engine (LogSearchService)**

- Strategy-based scanning: BACKWARD, FORWARD, BINARY_THEN_BACKWARD, BINARY_THEN_FORWARD
- Two-pass filtering: structural (during scan) + query (after merge)
- Parallel service scanning via dedicated resource-aware thread pool
- Two-layer in-memory cache: structural (shared by fingerprint) + CacheSession (per-user cursor/query/offsets)
- Multi-user support: 5 concurrent caches, 10 sessions per cache, LRU eviction, sliding 15-min TTL
- Bounded PriorityQueue merge (100K cap, progressive GC)
- Binary search with 200-line probe depth, 1MB safety margin, correct edge cases
- Cache metrics endpoint (cacheSize, sessionCount, reqCount via AtomicLong)
- System.gc() hint on cache clear for immediate heap reclamation
- ExportData record + getExportEntries() for streaming download of full cached results
- Historical log viewer: listServiceFiles() + searchHistorical() — browse and search archived/rotated files per service
- Historical cache fingerprint isolation ("h-" prefix + file path) prevents collision with live searches
- Path traversal security: rejects path separators/`..`, canonical path validation

**Frontend (index.html)**

- Virtual scroll: ~80 DOM entries, Float64Array positions, rAF-throttled, infinite scroll
- Bidirectional chip-sidebar sync (syncChipToSidebar / unsyncChipFromSidebar / syncSidebarToChips)
- Server time sync with toServerISO() conversion
- Live metrics bar: CPU, heap, RAM, GC, threads, uptime, cache stats — 4s polling, color-coded thresholds, smooth CSS transitions
- Export/Download toolbar: TXT + CSV format selector, streams all cached entries via backend endpoint
- Dedicated ▶/▼ expand/collapse toggle button per log entry (text freely selectable)
- Collapsible Tools section (Clear Cache, Health Check, Services Info)
- AbortController to cancel stale fetch requests
- XSS prevention: esc() with quote escaping
- updateCounts() optimized O(n) single pass
- Historical log viewer: Live/Historical mode toggle tabs, component+file dropdowns, doHistSearch()
- Per-tab state isolation: modeState object saves/restores entries, chips, sort, preset, filters on tab switch
- Services sidebar auto-dimmed in Historical mode (single component selected via dropdown)

**Parser (LogParserService)**

- 5 formats: ANSI colored, plain Log4j2, slim WES Log4j2, slim WES with {:} separator, ISO-8601 JVM startup
- isNewEntry() handles multiple ANSI prefix codes and T separator

**Configuration**

- Resource-aware pool sizing (scanPoolSize config)
- Context-path /loglens with Thymeleaf BASE injection
- showMetrics toggle for live resource metrics display
- 3 services with env var overrides (expandable to 15+)

**Testing (111 tests)**

- LogParserServiceTest: 28 tests (5 formats, ANSI multi-code, {:} separator, multiline/stack trace, edge cases)
- LogSearchServiceTest: 45 tests (cache isolation, pagination, offsets, file edge cases, .gz, export, hybrid query)
- MultiUserCacheIntegrationTest: 6 tests (concurrent access, session eviction)
- QueryEngineTest: 18 tests (AND/OR/NOT, fields, aliases, nesting, null entry, implicit juxtaposition)
- LogControllerTest: 14 tests (all REST endpoints, error handling, historical search, file listing)

**Load Test Results (stress_metrics.py — 5 users × 20 requests)**

- Fresh scans: 5-18s (15 services × 2.4 GB each)
- Cache hits: 0.02-0.4s (43-111x faster)
- Load More: 0.014s
- Peak heap: ~3.3 GB under 5-user concurrent load
- 362 GCs, 0 Full GCs
