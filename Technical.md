# LogLens Technical Deep Dive

## BACKWARD vs FORWARD Strategy — Key Differences

| Aspect                     | BACKWARD (scanBackward)                      | FORWARD (scanForward)                           |
| -------------------------- | -------------------------------------------- | ----------------------------------------------- |
| **I/O class**              | `RandomAccessFile`                           | `BufferedReader` + `FileInputStream`            |
| **Read direction**         | End of file → Start                          | Start of file → End                             |
| **Chunk size**             | 64 KB (manual, `CHUNK = 65536`)              | 8 KB (BufferedReader internal buffer)           |
| **How it reads**           | `seek()` + `readFully(bytes)`                | `readLine()` with internal 8 KB buffer          |
| **Syscalls per chunk**     | 2 (seek + readFully)                         | ~1 per 8 KB refill (transparent)                |
| **Line splitting**         | `chunk.split("\n")` in RAM after read        | `readLine()` returns one line at a time         |
| **Processing order**       | Right→Left within chunk (newest first)       | Top→Down (oldest first)                         |
| **Leftover handling**      | `lines[0]` carried as leftover to next chunk | Not needed (readLine handles it)                |
| **Early exit**             | `timestamp.isBefore(from)` → return          | `timestamp.isAfter(to)` → return                |
| **Best for**               | DESC sort, recent data, presets              | ASC sort, .gz files                             |
| **Byte position tracking** | `pointer = chunkStart` (updated each chunk)  | `bytePos += line.length() + 1` (manual)         |
| **Resume cursor**          | `e.setFileOffset(pointer)` — chunk boundary  | `e.setFileOffset(entryStartByte)` — entry start |

## Why Not Use RAF.readLine() for Forward Scanning?

`RandomAccessFile.readLine()` reads **one byte per OS syscall**. Internally it calls `read()` in a loop,
and each `read()` on RAF is an unbuffered native call. For a 700 MB file, that's ~700 million syscalls
— catastrophically slow.

`BufferedReader.readLine()` fills an 8 KB buffer with a single `read(char[], off, len)` call, then scans
characters in RAM. For the same file, that's ~85,000 syscalls (700 MB / 8 KB). Over 8,000x fewer syscalls.

## readFully() vs readLine() — The Core Difference

### What readFully() Does (used in BACKWARD)

```
raf.seek(chunkStart);           // 1 syscall: move file cursor to position
raf.readFully(bytes);           // 1 syscall: read exactly 65,536 bytes into byte[]
```

`readFully(byte[])` is a **bulk read**. It issues a single native `read()` call to the OS asking for the
entire 64 KB block. The OS reads the data from disk (or page cache) into the byte array in one operation.

After this, the code works entirely IN MEMORY:

```java
String chunk = new String(bytes, UTF_8) + leftover;   // byte[] → String (in RAM)
String[] lines = chunk.split("\n", -1);                // split in RAM
for (int i = lines.length - 1; i >= 1; i--) { ... }   // iterate in RAM
```

**Total syscalls for one 64 KB chunk: 2** (seek + readFully).

### What RAF.readLine() Does (NOT used — too slow)

```java
// Inside RandomAccessFile.readLine() — simplified
public final String readLine() throws IOException {
    StringBuilder buf = new StringBuilder();
    int c;
    while ((c = read()) != -1) {       // read() = 1 byte = 1 syscall EACH TIME
        if (c == '\n') break;
        buf.append((char) c);
    }
    return buf.toString();
}
```

Each `read()` inside RAF is an **unbuffered native call**. For a line of 200 characters, that's 200
separate OS syscalls just to read one line. For 100,000 lines averaging 200 chars, that's **20 million
syscalls** — each one involving a user-space → kernel-space context switch.

### What BufferedReader.readLine() Does (used in FORWARD)

```java
// Inside BufferedReader.readLine() — simplified
public String readLine() throws IOException {
    StringBuilder s = null;
    for (;;) {
        if (nextChar >= nChars) {
            fill();                     // Reads 8,192 chars in ONE syscall
        }
        // Scan through in-memory buffer for '\n'
        for (int i = nextChar; i < nChars; i++) {
            if (cb[i] == '\n') {
                // Found end of line — return substring from buffer
                return ... ;
            }
        }
    }
}
```

`fill()` reads 8 KB at once. Between refills, `readLine()` scans the in-memory `char[]` buffer looking
for `\n`. No syscalls during this scan — it's pure pointer arithmetic in RAM.

**Total syscalls for 8 KB of data: 1** (the fill() call).

### Comparison Table

| Method                      | Bytes read per syscall | Syscalls for 700 MB | Processing after read     |
| --------------------------- | ---------------------- | ------------------- | ------------------------- |
| `raf.readFully(64KB)`       | 65,536                 | ~10,700             | split + iterate in RAM    |
| `BufferedReader.readLine()` | 8,192                  | ~85,000             | scan char[] in RAM        |
| `raf.readLine()`            | **1**                  | **~700,000,000**    | build String byte-by-byte |

### The Misconception

> "Isn't backward scanning also reading char by char?"

**No.** Here's what actually happens:

**BACKWARD scan (readFully):**

```
Disk → [seek to position] → [readFully: 64 KB in ONE read] → byte[] in RAM
                                                                    ↓
                                                              new String(bytes)
                                                                    ↓
                                                              chunk.split("\n")
                                                                    ↓
                                                         iterate lines in RAM
```

The "char by char" scanning of lines happens **in RAM**, not from disk. The disk sees exactly 2 syscalls
per 64 KB chunk. Between chunks, there is zero disk I/O — everything is processed in memory.

**FORWARD scan (BufferedReader):**

```
Disk → [fill(): 8 KB in ONE read] → char[] buffer in RAM
                                          ↓
                                    scan for '\n' in RAM
                                          ↓
                                    return line String
                                          ↓
                                    (refill when buffer exhausted)
```

Same principle — disk reads are bulk, character scanning is in memory. Just a smaller buffer (8 KB vs 64 KB).

**RAF.readLine() — the BAD approach:**

```
Disk → [read(): 1 byte] → char     ← repeated for EVERY character
         ↓
    append to StringBuilder
         ↓
    [read(): 1 byte] → char        ← another syscall
         ↓
    append to StringBuilder
         ↓
    ... 200 times per line ...
```

EVERY character is a separate disk read + OS context switch. This is why it's ~8,000x slower.

## Summary: Total Disk I/O for One Search

### Configuration

- `maxScanLines = 100,000` (per service)
- `CHUNK = 65,536 bytes` (64 KB)
- Average line length: ~185 bytes (WES log format)
- Average lines per 64 KB chunk: ~350

### BACKWARD Strategy (typical DESC search, 3 services)

```
Lines to scan:        100,000 per service
Lines per chunk:      ~350
Chunks needed:        100,000 / 350 = ~286 chunks
Bytes per chunk:      64 KB
Disk read per service: 286 × 64 KB = ~18 MB
Syscalls per service:  286 × 2 = 572 (seek + readFully each)

Total across 3 services (parallel):
  Disk I/O:  18 MB × 3 = ~54 MB total
  Syscalls:  572 × 3 = 1,716 total
  Wall time: ~1-3 seconds (bottlenecked by slowest service)
```

### FORWARD Strategy (typical ASC search, 3 services)

```
Lines to scan:         100,000 per service
BufferedReader buffer:  8 KB
Bytes per fill:        8,192 bytes
Lines per fill:        ~44
Fills needed:          100,000 / 44 = ~2,273 fills
Disk read per service: 2,273 × 8 KB = ~18 MB
Syscalls per service:  2,273 (one per fill)

Total across 3 services (parallel):
  Disk I/O:  18 MB × 3 = ~54 MB total
  Syscalls:  2,273 × 3 = 6,819 total
  Wall time: ~1-3 seconds
```

### BINARY_THEN_BACKWARD (historical DESC, 3 services)

```
Binary search phase:
  Iterations:          ~20 per service (log2(700MB / 64KB))
  Reads per iteration: 1 seek + 1 readFully (64 KB) + up to 200 readLine calls
  Disk I/O:            ~20 × 64 KB = ~1.3 MB per service

Backward scan phase:   (same as BACKWARD above, but fewer chunks if range is narrow)
  If time range = 1 hour of a 12-hour file ≈ 1/12 of entries
  Chunks:              ~24 (8,333 lines / 350)
  Disk I/O:            ~1.5 MB per service

Total per service:     ~2.8 MB
Total across 3:        ~8.4 MB (much less than full BACKWARD)
```

### Key Insight

The total **bytes read from disk** is similar between BACKWARD and FORWARD (~18 MB per service) because
both ultimately scan the same 100,000 lines. The difference is **how efficiently those bytes are read**:

- BACKWARD: fewer, larger reads (64 KB each) = fewer syscalls
- FORWARD (BufferedReader): more, smaller reads (8 KB each) = more syscalls, but still fast
- FORWARD (RAF.readLine): individual bytes = millions of syscalls = **unusable**

The real performance advantage of BACKWARD isn't the I/O method — it's that for DESC (most recent first),
it reads from the END of the file where the freshest logs are, so it finds matching entries immediately
without scanning through hours of irrelevant older data.

## BINARY_THEN_FORWARD vs BINARY_THEN_BACKWARD

Both binary strategies have **two phases** that use different I/O methods.

### Phase 1 — Binary Search (same for both)

Uses `RandomAccessFile` + `raf.readLine()` (the slow byte-by-byte method — but only ~4,000 lines total, so it's fine).

```
Iteration 1:  seek(350 MB) → skip partial line → read up to 200 lines for a timestamp
              Found: 16:32 → target is 14:00 → too late → narrow search left

Iteration 2:  seek(175 MB) → skip partial → Found: 15:10 → still too late → narrow left

...~18 more iterations...

Iteration 20: Converged → byte position ~95 MB (where 14:00 starts)
```

Total I/O for Phase 1: ~20 seeks × ~200 lines × ~185 bytes = **~740 KB**. Negligible.

`raf.readLine()` is acceptable here because 4,000 lines = ~800K syscalls = milliseconds.
For 100,000 lines it would be 3.7 billion syscalls = minutes. That's why Phase 2 uses bulk reads.

### Phase 2 — The Actual Scan

| Aspect          | BINARY_THEN_FORWARD             | BINARY_THEN_BACKWARD               |
| --------------- | ------------------------------- | ----------------------------------- |
| **Seeks to**    | `fromTime` (start of window)    | `toTime` (end of window)            |
| **Then calls**  | `scanForward()` from that byte  | `scanBackward()` from that byte     |
| **I/O method**  | BufferedReader (8 KB buffer)    | RAF + readFully (64 KB chunks)      |
| **Direction**   | Forward (oldest → newest)       | Backward (newest → oldest)          |
| **Best for**    | ASC + time filter               | DESC + historical time range        |

Both reuse the exact same scan methods as the plain strategies:
- `BINARY_THEN_FORWARD` → Phase 1 finds `fromTime` byte position → calls `scanForward()` (BufferedReader, 8 KB)
- `BINARY_THEN_BACKWARD` → Phase 1 finds `toTime` byte position → calls `scanBackward()` (readFully, 64 KB)

### Visual Flow

```
BINARY_THEN_FORWARD (ASC + time filter on 700 MB file)
──────────────────────────────────────────────────────
Phase 1: Binary Search                   Phase 2: Forward Scan
┌──────────────────────────┐             ┌──────────────────────────┐
│ RAF + raf.readLine()     │             │ BufferedReader (8 KB)    │
│ ~20 iterations           │ ──found──►  │ readLine() from 95 MB    │
│ ~4,000 lines read        │  95 MB      │ 100,000 lines scanned    │
│ ~740 KB disk I/O         │             │ ~18 MB disk I/O          │
│ ~0.01 seconds            │             │ ~1-2 seconds             │
└──────────────────────────┘             └──────────────────────────┘

BINARY_THEN_BACKWARD (DESC + historical range on 700 MB file)
─────────────────────────────────────────────────────────────
Phase 1: Binary Search                   Phase 2: Backward Scan
┌──────────────────────────┐             ┌──────────────────────────┐
│ RAF + raf.readLine()     │             │ RAF + readFully (64 KB)  │
│ ~20 iterations           │ ──found──►  │ seek+readFully from      │
│ ~4,000 lines read        │  400 MB     │ 400 MB toward 0          │
│ ~740 KB disk I/O         │             │ 100,000 lines scanned    │
│ ~0.01 seconds            │             │ ~1-2 seconds             │
└──────────────────────────┘             └──────────────────────────┘
```

### Why Binary Search Avoids Scanning the Whole File

Without binary search, ASC + time filter (e.g., "show logs from 14:00") on a 700 MB file would scan
from byte 0, reading through hours of irrelevant data (11:31–13:59) before reaching 14:00.
Binary search jumps directly to ~95 MB in ~20 iterations, skipping ~95 MB of irrelevant data entirely.

For BACKWARD + historical range (e.g., "show logs from 14:00–15:00 DESC"), without binary search
it starts from byte 700 MB (end) and scans backward through 18:00, 17:00, 16:00 before reaching 15:00.
Binary search jumps to ~200 MB directly.

## Two-Pass Filtering — Pre-Scan vs Post-Scan

All four strategies scan up to **100,000 lines per service** from the log file. But not all 100K entries
make it to the UI. Filtering happens in two passes at different stages.

### Pass 1 — Structural Filters (DURING file scan)

Applied **inside** `scanBackward()` / `scanForward()` via `matchesStructured()`, line by line as each
entry is parsed from disk. Only entries that pass ALL structural filters survive into the results list.

**Structural filters (checked per entry during scan):**

| Filter     | Field checked        | Match type                  | Code (line 727-766)                    |
| ---------- | -------------------- | --------------------------- | -------------------------------------- |
| level      | `e.getLevel()`       | Exact match (case-insensitive) | `equalsIgnoreCase(level)`           |
| traceId(s) | `e.getTraceId()`     | Substring, OR across list   | `anyContains(traceIds, traceId)`       |
| spanId(s)  | `e.getSpanId()`      | Substring, OR across list   | `anyContains(spanIds, spanId)`         |
| userId     | `e.getUserId()`      | Substring (case-insensitive) | `contains(userId)`                    |
| siteId     | `e.getSiteId()`      | Substring (case-insensitive) | `contains(siteId)`                    |
| tenantId   | `e.getTenantId()`    | Substring (case-insensitive) | `contains(tenantId)`                  |
| logger     | `e.getLogger()`      | Substring (case-insensitive) | `toLowerCase().contains(logger)`      |
| message    | `e.getMessage()`     | Substring (case-insensitive) | `toLowerCase().contains(message)`     |
| from       | `e.getTimestamp()`   | `>= from`                   | `isBefore(from) → reject`             |
| to         | `e.getTimestamp()`   | `<= to`                     | `isAfter(to) → reject`                |

**Time filters also have early exit:**
- BACKWARD: if `timestamp.isBefore(from)` → `return results` (stop scanning — all older entries are irrelevant)
- FORWARD: if `timestamp.isAfter(to)` → `return results` (stop scanning — all newer entries are irrelevant)

This means time filters can short-circuit the scan and read far fewer than 100K lines.

### Pass 2 — Query Filter (AFTER scan, after merge + sort)

Applied in `search()` method (line 230-234), AFTER all services have been scanned, merged, and sorted.

```java
if (nb(req.getQuery())) {
    results = results.stream()
        .filter(e -> queryEngine.matches(e, req.getQuery()))
        .collect(Collectors.toList());
}
```

The query bar supports boolean expressions evaluated by `QueryEngine`:

| Query syntax             | What it searches                | Example                              |
| ------------------------ | ------------------------------- | ------------------------------------ |
| `keyword`                | message + rawLine (substring)   | `timeout`                            |
| `"quoted phrase"`        | message + rawLine (exact)       | `"containerId missing"`              |
| `field:value`            | Specific parsed field           | `level:ERROR`, `logger:DivertHandler`|
| `expr AND expr`          | Both must match                 | `level:ERROR AND userId:U001`        |
| `expr OR expr`           | Either matches                  | `level:ERROR OR level:WARN`          |
| `NOT expr`               | Negation                        | `NOT level:DEBUG`                    |
| `(expr)`                 | Grouping                        | `(level:ERROR OR level:WARN) AND service:routing` |

### Why Two Passes?

Structural filters run DURING scan because they can **reduce disk I/O**:
- Time range enables early exit (stop reading the file entirely)
- Field filters reduce the result list size, meaning less memory used during merge

Query filter runs AFTER scan because:
- It supports complex boolean logic (AND/OR/NOT/parentheses) that needs the full parsed entry
- It operates on merged, sorted results from ALL services
- It's applied in-memory — very fast, no disk I/O

### Flow Diagram

```
                     Per Service (parallel)
                    ┌──────────────────────────────────────────────┐
                    │                                              │
  Log File          │   SCAN (100K lines max)                      │
  (700 MB)  ──────► │     │                                        │
                    │     ├─ Parse each line → LogEntry             │
                    │     ├─ Check time range → early exit if out   │  ◄── Pass 1
                    │     ├─ matchesStructured() per entry          │      (DURING scan)
                    │     │    level? traceId? userId? siteId?      │
                    │     │    tenantId? logger? message? time?     │
                    │     └─ Only matching entries → results list   │
                    │                                              │
                    │   Output: e.g., 14,900 entries (from 100K)   │
                    └──────────────────┬───────────────────────────┘
                                       │
         ┌─────────────────────────────┤  (×3 services, parallel)
         │             │               │
         ▼             ▼               ▼
    Service A      Service B      Service C
    14,900         4,200          8,300
         │             │               │
         └─────────────┴───────┬───────┘
                               │
                        MERGE + SORT by timestamp
                               │
                        27,400 entries (structuredTotal)
                               │
                        ┌──────┴──────┐
                        │  Pass 2     │  ◄── Query filter (AFTER scan)
                        │  QueryEngine │      "level:ERROR AND message:timeout"
                        │  AND/OR/NOT │
                        └──────┬──────┘
                               │
                        12,150 entries (totalMatched)
                               │
                        PAGINATE (first 500)
                               │
                        500 entries → UI
```

### UI Shows Both Counts

The response includes both numbers:
- `filteredByStructured`: 27,400 — entries that passed Pass 1 (structural)
- `totalMatched`: 12,150 — entries that passed Pass 2 (query)
- UI displays: **"12,150 query matches from 27,400 filtered entries"**

### What This Means for the 100K Line Limit

The 100K limit applies to **lines scanned from disk**, not to matching entries. Example:

```
100,000 lines scanned from disk (per service)
    ↓ Pass 1: matchesStructured() removes non-matching
14,900 entries survive structural filters
    ↓ Merge 3 services
27,400 entries total
    ↓ Pass 2: QueryEngine removes non-matching
12,150 entries survive query filter
    ↓ Paginate
500 entries shown in UI
```

If filters are very selective (e.g., traceId filter), most of the 100K lines get rejected in Pass 1,
and the results list stays small. If no filters are set, all 100K parsed entries survive Pass 1 and
go into the merge.
