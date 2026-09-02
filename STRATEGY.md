# LogLens Development Strategy

> **This document governs ALL implementation work on LogLens.**
> Claude: read this file at the start of every session. Follow the roles, phases, and gates exactly.
> User: this is the agreed-upon process. Any deviation must be explicitly requested.

## Organizational Roles

Claude operates as a full engineering team. Each "role" is a mode of thinking with specific responsibilities and quality gates. Switch roles explicitly during work.

### CEO (Decision Authority)

- **Who**: The user (Mandip)
- **Responsibility**: Approves plans, resolves open design questions, decides priorities, gives go/no-go for each phase
- **Gate**: Nothing moves to implementation without CEO sign-off on the plan

### CTO (Architecture)

- **Who**: Claude in planning mode
- **Responsibility**: Designs solutions, identifies risks, estimates impact, writes technical plans in TODO-scalability.md
- **Gate**: Plan must include: problem statement, proposed solution, files to change, memory/performance impact, test criteria
- **Rule**: Never propose a solution without reading ALL affected files first

### Tech Lead (Code Review)

- **Who**: Claude in review mode
- **Responsibility**: Reviews completed code against plan, checks coding conventions, verifies no regressions
- **Gate**: Code must match the plan. If code deviates, it loops back to CTO for plan update
- **Checklist**:
  - [ ] 2-space indentation
  - [ ] Import order: java.\* first, then lombok/spring
  - [ ] Lombok annotations used (@Slf4j, @Data, @Builder, @RequiredArgsConstructor)
  - [ ] No over-engineering beyond what was planned
  - [ ] No hardcoded values that should be configurable
  - [ ] rawLine/stackTrace fields handled carefully (memory impact)

### Developer (Implementation)

- **Who**: Claude in coding mode
- **Responsibility**: Writes code exactly per the approved plan. No scope creep, no "improvements" beyond plan
- **Rule**: Read every file you're about to modify BEFORE writing a single line
- **Rule**: Make the minimum change needed. If you want to refactor adjacent code, stop and ask CEO

### QA Engineer (Testing)

- **Who**: Claude in testing mode
- **Responsibility**: Builds test scenarios, runs tests, documents results, identifies regressions
- **Gate**: ALL tests must pass. Zero tolerance for "we'll fix it later"
- **Rule**: Test against the SAME load_test.py scenarios. Compare numbers with baseline
- **Rule**: If a test fails, DO NOT modify the test to make it pass. Fix the code instead

### DevOps (Build & Deploy)

- **Who**: Claude managing build/run
- **Responsibility**: Builds JAR, starts app with monitoring flags, captures GC/memory data, stops app
- **Standard flags**: `java -Xmx4g -Xlog:gc*:file=gc_loadtest.log:time,uptime,level,tags -jar build/libs/loglens-<version>-exec.jar`

---

## Development Lifecycle

Every priority (P0, P1, P2) follows this exact lifecycle. No shortcuts.

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│   ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐ │
│   │  1. PLAN  │────►│  2. CODE │────►│  3. TEST │────►│ 4. REVIEW│ │
│   │  (CTO)   │     │  (Dev)   │     │  (QA)    │     │ (TechLead│ │
│   └──────────┘     └──────────┘     └────┬─────┘     └────┬─────┘ │
│        ▲                                  │                │       │
│        │                                  │ Fail           │ Fail  │
│        │           ┌──────────┐           │                │       │
│        │           │  5. FIX  │◄──────────┘                │       │
│        │           │  (Dev)   │                            │       │
│        │           └────┬─────┘                            │       │
│        │                │                                  │       │
│        │                └──────────► back to 3. TEST       │       │
│        │                                                   │       │
│        └───────────────────────────────────────────────────┘       │
│                          (Review fail = back to Plan)              │
│                                                                     │
│   On PASS: ┌──────────────┐                                        │
│            │ 6. SIGN-OFF  │  CEO approves → update CLAUDE.md,      │
│            │    (CEO)     │  update TODO status, move to next P    │
│            └──────────────┘                                        │
└─────────────────────────────────────────────────────────────────────┘
```

### Phase Details

#### Phase 1: PLAN (CTO)

1. Read ALL files listed in "Files to Change" for the current priority
2. Read the plan in TODO-scalability.md
3. Write a detailed implementation plan with:
   - Exact code changes (method signatures, field additions)
   - Order of changes (which file first)
   - What NOT to change (explicit scope boundary)
   - Test criteria: what passes, what's expected
4. Present plan to CEO for approval
5. **Gate**: CEO says "go" before any code is written

#### Phase 2: CODE (Developer)

1. Implement EXACTLY what was planned. Nothing more.
2. After each file change, re-read the file to verify the edit is correct
3. Run `./gradlew compileJava` after all changes to catch compile errors
4. Do NOT run tests yet — that's QA's job
5. **Gate**: Code compiles cleanly

#### Phase 3: TEST (QA)

1. Build: `./gradlew bootJar`
2. Start app: `java -Xmx4g -Xlog:gc*:file=gc_loadtest.log:time,uptime,level,tags -jar ...`
3. Run: `python load_test.py` (the SAME test suite — do not modify tests)
4. Capture: all test results + GC stats + timing
5. Compare against baseline (see Load Test Baseline section below)
6. **Gate**: ALL tests pass. Performance not worse than baseline. No new GC issues.

#### Phase 4: FIX (Developer, if tests fail)

1. Analyze failure: which test, what error, what changed
2. Fix the root cause — do NOT modify the test
3. Return to Phase 3 (TEST). Loop until all pass.
4. **Max iterations**: 3 fix cycles. If still failing after 3, escalate back to PLAN.

#### Phase 5: REVIEW (Tech Lead)

1. Re-read all changed files
2. Verify against the plan: does the code match what was approved?
3. Check coding conventions (2-space indent, imports, Lombok, etc.)
4. Check for memory leaks, unclosed resources, thread safety issues
5. Check for regressions in areas not covered by load_test.py
6. **Gate**: Code quality acceptable. If not → back to CODE or PLAN.

#### Phase 6: SIGN-OFF (CEO)

1. Present summary: what changed, test results, performance comparison
2. CEO approves → update TODO-scalability.md status, update CLAUDE.md changelog
3. Move to next priority

---

## Session Recovery Protocol

When a new session starts (context lost):

1. Read `CLAUDE.md` (auto-loaded)
2. Read `STRATEGY.md` (this file)
3. Read `TODO-scalability.md` → find which priority is current
4. Check the `Implementation Status` section below for current phase
5. Resume from the exact phase noted

**Never restart a completed priority. Never skip a phase.**

---

## Implementation Status

Track current state here. Update after each phase completes.

| Priority                     | Status        | Current Phase | Notes                     |
| ---------------------------- | ------------- | ------------- | ------------------------- |
| **P0a: Thread Pool**         | `DONE`        | Complete      | 36/36 PASS. 2.2x faster. Signed off 2026-03-30. |
| **P0b: Per-Service Offsets** | `DONE`        | Complete      | Offset plumbing done. Pagination correctness deferred to P1b (cache). |
| **P1b: In-Memory Cache**     | `DONE`        | Complete      | Two-layer cache, 5 concurrent users (LRU), sliding 15-min TTL, cache clear API + UI tools. 36/36 PASS. Queries 43-111x faster. Total 46.5s (was 163s). Signed off 2026-03-31. |
| **P1a: Streaming Merge**     | `DONE`        | Complete      | Bounded PriorityQueue merge (100K cap). 51/51 PASS (15 new tests added). Humongous allocs 685->330 (52% drop). Sort 15x faster (100K vs 1.3M). 0 Full GCs. |
| **P2: DOM Virtualization**   | `TESTING`     | Browser test  | Virtual scroll implemented. Only ~80 DOM entries rendered. Backend 51/51 PASS. Needs manual browser testing. |

Status values: `NOT_STARTED` → `PLANNING` → `CODING` → `TESTING` → `FIXING` → `REVIEWING` → `DONE`

---

## Load Test Baseline (13 Services × 2.5 GB)

**Test date**: 2026-03-30
**Environment**: 22 CPUs, 32 GB RAM, Java 21, -Xmx4g, Windows 11
**Services**: 13 (MC + 12 clones, identical 2.5 GB data via hardlink)
**Test script**: `load_test.py` (36 tests)

### Results: 36/36 PASSED

```
#     Test                                              Time    Matched   Structural Strategy
───── ───────────────────────────────────────────────── ─────── ───────── ────────── ─────────────────────
1     T1:  DESC no filter                               7.618s  1,301,469 1,301,469  BACKWARD
2     T2:  ASC no filter                                7.356s  1,179,750 1,179,750  FORWARD
3     T3:  DESC + recent traceId (no time)              3.307s  130       130        BACKWARD
4     T4:  ASC + recent traceId (no time)               3.146s  130       130        BACKWARD
5     T5:  DESC + recent spanId (no time)               2.620s  130       130        BACKWARD
6     T6:  DESC + recent traceId + time range           0.500s  130       130        BINARY_THEN_BACKWARD
7     T7:  DESC + old traceId (NO time — expected 0)    2.610s  0         0          BACKWARD
8     T8:  DESC + old traceId + time range              2.572s  429       429        BINARY_THEN_BACKWARD
9     T9:  ASC + old traceId + time range               2.291s  429       429        BINARY_THEN_FORWARD
10    T10: DESC + old spanId (NO time — expected 0)     2.826s  0         0          BACKWARD
11    T11: DESC + level=DEBUG                           5.357s  887,679   887,679    BACKWARD
12    T12: DESC + level=INFO                            3.644s  413,439   413,439    BACKWARD
13    T13: DESC + level=WARN                            3.066s  351       351        BACKWARD
14    T14: DESC + level=ERROR (expected 0 in window)    4.051s  0         0          BACKWARD
15    T15: DESC + level=ERROR + historical time         2.955s  2,028     2,028      BINARY_THEN_BACKWARD
16    T16: DESC + userId                                4.666s  856,518   856,518    BACKWARD
17    T17: DESC + siteId                                4.459s  856,518   856,518    BACKWARD
18    T18: DESC + tenantId                              4.410s  856,518   856,518    BACKWARD
19    T19: DESC + logger filter                         3.845s  411,008   411,008    BACKWARD
20    T20: DESC + message filter                        3.384s  30,017    30,017     BACKWARD
21    T21: DESC + recent time range                     3.105s  607,555   607,555    BINARY_THEN_BACKWARD
22    T22: DESC + historical time range                 4.776s  941,486   941,486    BINARY_THEN_BACKWARD
23    T23: ASC + time range (BINARY_THEN_FORWARD)       4.971s  941,473   941,473    BINARY_THEN_FORWARD
24    T24: Query keyword (Heartbeat)                    8.632s  616,694   1,301,469  BACKWARD
25    T25: Query boolean (WARN AND timeout)             8.643s  351       1,301,469  BACKWARD
26    T26: Query field (logger:ByteArray...)            8.959s  411,008   1,301,469  BACKWARD
27    T27: Complex boolean (INFO|WARN AND Heartbeat)    11.446s 175,773   1,301,469  BACKWARD
28    T28: Query NOT (NOT level:DEBUG)                  7.971s  413,790   1,301,469  BACKWARD
29    T29: traceId + userId + time                      2.685s  429       429        BINARY_THEN_BACKWARD
30    T30: level=INFO + logger + message                2.016s  205,504   205,504    BACKWARD
31    T31: WARN + query (Heartbeat)                     1.896s  351       351        BACKWARD
32    T32a: Page 1 (DESC no filter)                     4.900s  1,301,469 1,301,469  BACKWARD
33    T32b: Page 2 (Load More)                          5.510s  1,301,456 1,301,456  BACKWARD
34    T33: Single service only                          1.431s  100,113   100,113    BACKWARD
35    T34: Large limit=5000                             5.605s  1,301,469 1,301,469  BACKWARD
36    T35: Limit=0 All (safeCap=50000)                  5.873s  1,301,469 1,301,469  BACKWARD
```

### Performance Baseline

| Metric  | Value                                |
| ------- | ------------------------------------ |
| Fastest | 0.500s (T6: binary search + traceId) |
| Slowest | 11.446s (T27: complex boolean query) |
| Average | 4.531s                               |
| Total   | 163.10s                              |

### Memory/GC Baseline

| Metric                | Value                    |
| --------------------- | ------------------------ |
| Peak heap before GC   | 2,718 MB (of 4 GB)       |
| Max heap capacity     | 3,068 MB                 |
| Total GC events       | 933 (800 cycles in 180s) |
| Full GC events        | 0                        |
| Humongous allocations | 685                      |
| Max GC pause          | 75.9 ms                  |
| Avg GC pause          | 11.2 ms                  |
| Total GC time         | 10.5s (5.8% of runtime)  |

### Expected 0-Result Tests

| Test | Reason                                                              |
| ---- | ------------------------------------------------------------------- |
| T7   | Old traceId outside 100K backward scan window (12h before file end) |
| T10  | Old spanId outside 100K backward scan window                        |
| T14  | ERROR entries only at file start; scan window covers last ~3.2h     |

### Key Observations

| Finding                         | Evidence                                                               | Fix                                         |
| ------------------------------- | ---------------------------------------------------------------------- | ------------------------------------------- |
| Memory critical: 2.7 GB peak    | Single search on 13 services. Two concurrent = OOM                     | P1a: Streaming merge                        |
| Query engine bottleneck         | T31=1.9s (structural WARN) vs T25=8.6s (query WARN) — same 351 results | P1a: Merge query into bounded heap          |
| 685 humongous allocations       | Large String objects (rawLine) > 50% of G1 region                      | P1a: Bounded heap reduces objects in flight |
| Thread pool adequate on 22-core | Dev machine has 21 ForkJoin threads. 4-core prod = only 3              | P0a: Dedicated pool                         |
| Pagination works but fragile    | T32 passes only because all files are identical hardlinks              | P0b: Per-service offsets                    |
| Binary search excellent         | T6=0.5s vs T3=3.3s (6.6x faster with time range)                       | No fix needed                               |

---

## Acceptance Criteria Per Priority

### P0a: Dedicated Thread Pool — DONE when:

- [ ] Custom `ExecutorService` in `LogSearchService`, not `ForkJoinPool.commonPool()`
- [ ] Pool size: `min(serviceCount, max(2, availableCores × 0.6))` or configurable
- [ ] `@PreDestroy` shutdown hook
- [ ] load_test.py: 36/36 PASS
- [ ] No performance regression vs baseline (times within ±20%)
- [ ] GC metrics: no worse than baseline

### P0b: Per-Service Offsets — DONE when:

- [ ] `Map<String, Long> nextOffsets` in SearchResult (replaces single nextOffset)
- [ ] `Map<String, Long> serviceOffsets` in SearchRequest
- [ ] Each service resumes from its OWN byte position on Load More
- [ ] Frontend sends/receives per-service offset map
- [ ] T32 (pagination) works with different-sized files (not just identical hardlinks)
- [ ] Backward compat: old `fileOffset` still works for single-service searches
- [ ] load_test.py: 36/36 PASS

### P1a: Streaming Merge — DONE when:

- [x] Merge uses bounded PriorityQueue (size = CACHE_MAX_ENTRIES), not ArrayList of all entries
- [x] Query filter applied from cache Layer 2 (bounded by cache, not unbounded list)
- [x] Peak heap reduced for merge phase (scan phase still dominates at 3.7 GB; merge no longer amplifies)
- [x] `filteredByStructured` count still accurate (structuralTotal counter)
- [x] load_test.py: 51/51 PASS (15 new tests: cache reuse, sort order, multi-value, deep pagination, cache clear)
- [x] GC humongous allocations reduced: 685 -> 330 (52% drop)

### P1b: In-Memory Cache — DONE when:

- [x] SearchCache with searchId, entries, cursor, serviceOffsets, TTL
- [x] Load More served from cache (0 disk I/O) when cache is warm
- [x] TTL eviction + max concurrent caches
- [x] New search invalidates old cache
- [x] T32 (Load More): < 0.5s on cache hit (vs current 5.5s)
- [x] load_test.py: 36/36 PASS

### P2: DOM Virtualization — DONE when:

- [ ] Only ~80 DOM entries rendered regardless of total count
- [ ] Scrolling smooth at 5,000+ entries
- [ ] Keyword highlighting only on visible entries
- [ ] Expand/collapse works correctly within virtual scroll
- [ ] Manual browser testing passes

---

## Rules That Never Change

1. **Never auto-commit or auto-push.** User pushes from their own CLI.
2. **Never modify tests to make them pass.** Fix the code instead.
3. **Never implement multiple priorities at once.** Complete P0a before starting P0b.
4. **Never skip a phase.** Plan → Code → Test → Fix-loop → Review → Sign-off.
5. **Always read files before editing them.**
6. **2-space Java indentation. Always.**
7. **Update CLAUDE.md after every completed priority.**
8. **Update this file's Implementation Status after every phase transition.**
