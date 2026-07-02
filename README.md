# core-loglens

A lightweight, browser-based log viewer for Honeywell WES microservices.
LogLens reads log files directly from the filesystem — no ELK stack, no Kafka, no agents, no indexing, no database.

---

## Features

- **Multi-service** — monitor 3 to 15+ WES services side-by-side with elastic resource scaling
- **Historical log viewer** — browse and search archived/rotated log files (`.log.1`, `.log.gz`, etc.) per service via a dedicated Historical Logs tab with component and file selection
- **Two-pass filtering** — structural filters (level, traceId, service, time, etc.) run first during file scan; the free-form query bar filters on top of those results
- **Free-form query engine** — `level:ERROR AND userId:U001`, `traceId:abc NOT level:DEBUG`, quoted phrases, parenthesized groups
- **Structured filters** — level, service, traceId, spanId, userId, siteId, tenantId, logger, message keyword, time range — all applied during the file scan pass
- **Service filter** — text input in the sidebar for filtering by service name; if specific services are selected, only those are searched; if none, all configured services are searched
- **Time presets** — 5m / 15m / 30m / 1h / 6h / 24h anchored to server clock (timezone-safe)
- **Click-to-filter** — click any field value in results to add it as a filter chip; the chip auto-fills the corresponding sidebar input for editing
- **Chip-sidebar sync** — bidirectional: clicking a result value populates the sidebar input; removing a chip clears it; editing the sidebar input updates the search
- **Stack trace viewer** — full multiline log blocks with syntax-highlighted stack traces
- **Column picker** — show/hide any field column on the fly
- **Gzip support** — reads `.log.gz` rotated files transparently
- **Parallel scanning** — each service file is scanned concurrently via dedicated thread pool (resource-aware sizing)
- **Strategy-based scanning** — automatic selection of BACKWARD, FORWARD, BINARY_THEN_BACKWARD, or BINARY_THEN_FORWARD per file per request
- **Two-layer in-memory cache** — structural results cached (up to 100K entries), query changes filter from cache with zero disk I/O (43-111x faster)
- **Multi-user support** — up to 5 concurrent caches with sliding 15-min TTL and LRU eviction
- **Bounded merge** — PriorityQueue-based merge caps at 100K entries regardless of input volume
- **Virtual scroll** — renders only ~80 visible DOM entries regardless of total count (smooth at 50K+ entries)
- **Infinite scroll** — auto-triggers Load More when within 300px of bottom
- **Server time sync** — header clock shows server time once synced; all timestamps (presets and manual input) are consistently in server time
- **Admin tools** — collapsible Tools section with Clear Cache, Health Check, Services Info
- **Live metrics bar** — real-time CPU, memory, heap, GC, threads, uptime, and cache stats in the header; configurable via `show-metrics` toggle
- **Export/Download** — download all filtered log entries as TXT (raw log lines) or CSV (structured columns); streams from backend cache (up to 100K entries) with proper RFC 4180 escaping
- **Scan depth control** — per-search scan depth dropdown (100K to 10M lines) in the toolbar; server caps at configurable ceiling (`max-scan-lines-limit`)
- **Hybrid query mode** — when scan depth exceeds the default (100K), the free-form query is applied _before_ the PQ merge/cache cap, so rare matches deep in large files are not lost to the 100K entry limit; default scan depth preserves instant Layer 2 refilter
- **Text-selectable log entries** — dedicated ▶/▼ expand/collapse toggle button; clicking text selects it instead of toggling expand
- **Zero external dependencies** — pure Spring Boot + Thymeleaf, single executable JAR

---

## Tech Stack

| Layer       | Technology                                                    |
| ----------- | ------------------------------------------------------------- |
| Language    | Java 21                                                       |
| Framework   | Spring Boot 3.5.8 — embedded Tomcat, config binding, REST API |
| Templating  | Thymeleaf — server-side HTML                                  |
| Build       | Gradle 8.14.2, version 2.1.2-SNAPSHOT                         |
| Boilerplate | Lombok — `@Data`, `@Builder`, getters/setters                 |
| JSON        | Jackson with `JavaTimeModule` — ISO date serialization        |
| UI          | Vanilla JS + HTML — single-file, no npm, no build step        |

---

## Architecture

### Two-Pass Filtering

```
Pass 1 — Structural Filters (during file scan)
  Checks: level, traceId(s), spanId(s), userId, siteId, tenantId,
          logger, message, time range (from/to).
  Only matching entries survive to the merge step.

Pass 2 — Free-Form Query (after merge + sort)
  Applies the query bar expression (boolean logic, field:value, keywords).
  Narrows the structural results further.
```

The UI displays both counts: e.g., "150 query matches from 2,340 filtered entries."

**Hybrid Query Mode:** When scan depth > default (100K), Pass 2 runs _before_ the PQ merge — only query-matching entries occupy cache slots, maximizing recall for rare matches in deep scans. At default scan depth, Pass 2 runs from cache (instant, zero disk I/O).

### Scan Strategy Pattern

| Scenario                           | Strategy               | How It Works                                              |
| ---------------------------------- | ---------------------- | --------------------------------------------------------- |
| `.gz` compressed file              | `FORWARD`              | BufferedReader via GZIPInputStream (cannot seek in gzip)  |
| ASC + any time filter              | `BINARY_THEN_FORWARD`  | Binary search to `fromTime` (or file start), then forward |
| ASC + structural filters (no time) | `BACKWARD`             | Data likely recent; post-merge sort orders ASC            |
| ASC + no time filter + no filters  | `FORWARD`              | BufferedReader + FileInputStream with byte tracking       |
| DESC + any time filter             | `BINARY_THEN_BACKWARD` | Binary search to `toTime` (~20 seeks), then backward scan |
| DESC + no time filter (default)    | `BACKWARD`             | 64KB chunks from end of file toward start                 |

### Parallel Service Scanning

Dedicated resource-aware thread pool: `poolSize = min(serviceCount, min(cores * 0.6, heapMB / 150))`

3 services = at most 3 threads. 15 services on 8-core = 4 threads. Scales with actual load.

### Two-Layer In-Memory Cache

Max 5 concurrent caches. Layer 1 = structural results (100K max). Layer 2 = query-filtered view.
Cache hits are 43-111x faster. Load More from cache: ~0.014s. Sliding TTL 15 min. LRU eviction.

### Bounded PriorityQueue Merge

Caps at 100K entries. DESC keeps newest, ASC keeps oldest. Progressive GC release per-service.

### Virtual Scroll (Frontend)

~80 visible DOM entries. Float64Array positions. rAF-throttled. Infinite scroll at 300px from bottom.

---

## Resource Scaling (3 to 15 Services)

LogLens scales elastically — resource consumption is proportional to actual service count.

### Thread Pool

| Services | 4-core / 512 MB | 8-core / 2 GB | 16-core / 4 GB |
| -------- | --------------- | ------------- | -------------- |
| 3        | 2 threads       | 3 threads     | 3 threads      |
| 8        | 2 threads       | 4 threads     | 8 threads      |
| 15       | 2 threads       | 4 threads     | 9 threads      |

### Memory (JVM Heap)

| Scenario                 | 3 svcs  | 8 svcs  | 15 svcs |
| ------------------------ | ------- | ------- | ------- |
| Idle                     | ~80 MB  | ~80 MB  | ~80 MB  |
| 1 user, narrow filter    | ~160 MB | ~200 MB | ~250 MB |
| 5 users, all caches full | ~400 MB | ~600 MB | ~800 MB |

### Response Time

| Scenario              | 3 svcs     | 8 svcs     | 15 svcs    |
| --------------------- | ---------- | ---------- | ---------- |
| Load More (cache)     | 0.01-0.02s | 0.01-0.02s | 0.01-0.02s |
| Query change (cache)  | 0.02-0.1s  | 0.02-0.4s  | 0.02-0.4s  |
| Fresh scan + preset   | 1-3s       | 2-5s       | 3-8s       |
| Fresh scan, no filter | 3-5s       | 5-10s      | 8-15s      |

### Recommended JVM Sizing

| Deployment | Services | `-Xmx` | `-Xms` |
| ---------- | -------- | ------ | ------ |
| Small      | 3-5      | 512 MB | 256 MB |
| Medium     | 6-10     | 1-2 GB | 512 MB |
| Large      | 11-15    | 2-4 GB | 1 GB   |

---

## Project Structure

```
core-loglens/
├── build.gradle
├── settings.gradle
├── gradle.properties                    # Version + Artifactory credentials
└── src/main/
    ├── java/com/honeywell/loglens/
    │   ├── LogLensApplication.java      # Spring Boot entry point
    │   ├── config/
    │   │   ├── LogLensConfig.java       # Binds loglens.* from application.yml
    │   │   └── JacksonConfig.java       # ObjectMapper with JavaTimeModule
    │   ├── model/
    │   │   ├── LogEntry.java            # Parsed log record (14 fields)
    │   │   ├── SearchRequest.java       # Search params (searchId, maxScanLines override)
    │   │   └── ServiceConfig.java       # Service definition (name/logFile/color)
    │   ├── controller/
    │   │   ├── LogController.java       # REST endpoints + historical search + export + cache/clear + metrics
    │   │   └── UIController.java        # Serves index.html via Thymeleaf
    │   └── service/
    │       ├── LogParserService.java    # 4 log formats: ANSI, Plain, Slim, ISO
    │       ├── LogSearchService.java    # Core: scanning, caching, merge, export, historical search (~1370 lines)
    │       └── QueryEngine.java         # Boolean query parser
    └── resources/
        ├── application.yml              # Config: port, services, limits, pool, metrics
        └── templates/
            └── index.html               # Single-page UI (~1904 lines)
```

---

## Configuration

All configuration in `src/main/resources/application.yml`. Key properties:

```yaml
server:
  port: 8090
  servlet:
    context-path: /loglens

loglens:
  tail-lines: 500
  max-scan-lines: 100000 # default scan depth per search
  max-scan-lines-limit: 10000000 # hard ceiling for per-request override
  scan-pool-size: 0 # 0 = auto, or explicit thread count
  show-metrics: true # show live resource metrics in the header bar
```

### Adding a New Service

```yaml
- name: your-service
  log-file: ${LOGLENS_YOURSERVICE_LOG:/path/to/your-service-console.log}
  color: "#15803d"
```

No code changes needed — just rebuild and restart.

### Environment Variable Overrides

Log paths support `${ENV_VAR:default}` syntax. See examples for Linux, CMD, and PowerShell below.

**Linux / Git Bash:**

```bash
LOGLENS_ROUTING_LOG=/opt/logs/routing.log java -jar build/libs/loglens-2.1.2-SNAPSHOT-exec.jar
```

**CMD:** `set LOGLENS_ROUTING_LOG=C:\path\to\routing.log && gradlew bootRun`

**PowerShell:** `$env:LOGLENS_ROUTING_LOG="C:\path\to\routing.log"; ./gradlew bootRun`

---

## Build & Run

```bash
./gradlew clean bootJar
# Output: build/libs/loglens-2.1.2-SNAPSHOT-exec.jar

java -jar build/libs/loglens-2.1.2-SNAPSHOT-exec.jar
# With custom heap for 10+ services:
java -Xmx2g -jar build/libs/loglens-2.1.2-SNAPSHOT-exec.jar
```

Access: `http://<hostname>:8090/loglens`

---

## REST API

| Method | Endpoint                                  | Description                                      |
| ------ | ----------------------------------------- | ------------------------------------------------ |
| `POST` | `/loglens/api/logs/search`                | Two-pass search with caching                     |
| `GET`  | `/loglens/api/logs/services`              | List services and file status                    |
| `GET`  | `/loglens/api/logs/services/{name}/files` | List archived/historical log files for a service |
| `POST` | `/loglens/api/logs/search/historical`     | Search a single historical/archived log file     |
| `GET`  | `/loglens/api/logs/servertime`            | Server local time (timezone sync)                |
| `GET`  | `/loglens/api/logs/metrics`               | Live resource metrics (CPU, memory, GC, cache)   |
| `GET`  | `/loglens/api/logs/export`                | Download filtered logs as TXT or CSV             |
| `GET`  | `/loglens/api/logs/health`                | Health check                                     |
| `POST` | `/loglens/api/logs/cache/clear`           | Clear all caches + GC hint                       |

---

## Query Syntax

```bash
level:ERROR                              # Single field
"containerId missing"                    # Phrase
level:ERROR AND userId:redsUser          # Boolean AND
level:ERROR OR level:WARN                # Boolean OR
NOT level:DEBUG                          # Negation
(level:ERROR OR level:WARN) AND service:routing  # Grouped
```

**Fields:** `level`, `userId`/`user`, `siteId`/`site`, `tenantId`/`tenant`, `traceId`/`trace`, `spanId`/`span`, `logger`/`class`, `service`, `message`/`msg`, `thread`

---

## Log Parsing

Five formats: ANSI colored (ESC codes stripped), Plain Log4j2, Slim WES Log4j2 (no hostname/PID), Slim WES with `{:}` brace separator, ISO-8601 JVM startup.
`isNewEntry()` is O(1) — checks `YYYY-MM-DD` pattern with optional ANSI prefix.

---

## Testing

111 JUnit 5 tests across 5 test classes:

| Test Class                      | Tests | Coverage                                                                                    |
| ------------------------------- | ----- | ------------------------------------------------------------------------------------------- |
| `LogParserServiceTest`          | 28    | 5 formats, ANSI multi-code, {:} separator, multiline/stack trace, edge cases                |
| `LogSearchServiceTest`          | 45    | Cache isolation, pagination, offsets, filters, .gz, export, historical search, hybrid query |
| `MultiUserCacheIntegrationTest` | 6     | Concurrent access, session eviction                                                         |
| `QueryEngineTest`               | 18    | AND/OR/NOT, fields, aliases, nesting, null entry, implicit juxtaposition                    |
| `LogControllerTest`             | 14    | All REST endpoints including historical, error handling                                     |

```bash
./gradlew test
```

---

## Publish

```bash
./gradlew publish
# SNAPSHOT → snapshot repo, Release → stable repo
```

---

## Version

**2.1.2-SNAPSHOT** — Coverity/BlackDuck remediation: fixed CWE-117 (log injection), CWE-476 (null pointer), CWE-252 (unchecked return), JLM (sync on CHM), DM_GC (System.gc), DLS (dead stores), untyped matchers. 114 unit tests.

---

## Author

**Mandip Pandit** (H504024) — Honeywell
