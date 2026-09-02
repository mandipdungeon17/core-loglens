"""
LogLens Load Test v2 - 13 Services x 2.5 GB Each (MC + 12 clones)
All test data verified from the actual log file's last 100K lines (backward scan window).

VERIFIED test data (from momentumconnect-console.log, 2.5 GB):
  File time range:     2026-03-18T13:04:13  to  2026-03-26T03:37:09
  Last 100K lines:     2026-03-26T00:24:34  to  2026-03-26T03:37:09 (~3.2 hours)

  WITHIN 100K window (BACKWARD scan will find these):
    traceId:  ffdef2f52c2096d0  (10 hits, spanId: 1cd29a803457848d, timestamp: 2026-03-26 03:36)
    userId:   redsUser          (65,807 entries in 100K window)
    siteId:   redsSite          (same entries as userId)
    tenantId: redsOrg           (same entries as userId)
    Levels:   DEBUG=68,205  INFO=31,768  WARN=27  ERROR=0
    Logger:   ByteArrayStxEtxSerializer (present in 100K window)
    Message:  "Deserialize" (present), "Heartbeat" (present)

  OUTSIDE 100K window (needs time range for binary search):
    traceId:  b72f427db0b11b43  (33 hits, timestamp: 2026-03-25 14:57 — 12h before file end)
    ERROR entries: only at file start (2026-03-18T13:04)

  Services: 13 (momentumconnect + service01-service12, all same physical data via hardlink)

Usage: python load_test.py [base_url]
"""

import json
import time
import sys
import urllib.request
import urllib.error

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8090/loglens"
API = BASE + "/api/logs"

# -- VERIFIED: within last 100K lines (backward scan finds these) ------
RECENT_TRACE_ID = "ffdef2f52c2096d0"       # 10 hits/file, at 2026-03-26 03:36, 9 DEBUG + 1 INFO
RECENT_SPAN_ID = "1cd29a803457848d"         # paired with ffdef2f52c2096d0
RECENT_TRACE_TIME = "2026-03-26T03:36"      # exact timestamp of this trace
USER_ID = "redsUser"                         # 65,807 entries in 100K window
SITE_ID = "redsSite"
TENANT_ID = "redsOrg"
LOGGER_FILTER = "ByteArrayStxEtxSerializer"
MESSAGE_FILTER = "Deserialize"
QUERY_KEYWORD = "Heartbeat"

# -- VERIFIED: outside 100K window (needs binary search) ---------------
OLD_TRACE_ID = "b72f427db0b11b43"           # 33 hits/file, at 2026-03-25 14:57
OLD_SPAN_ID = "84fc9f4a135b438f"            # paired with b72f427db0b11b43

# -- Time boundaries ---------------------------------------------------
FILE_START = "2026-03-18T13:00"
FILE_END = "2026-03-26T03:37"
SCAN_WINDOW_START = "2026-03-26T00:24"      # where 100K backward scan reaches
HISTORICAL_FROM = "2026-03-18T13:00"
HISTORICAL_TO = "2026-03-18T15:00"
OLD_TRACE_FROM = "2026-03-25T14:00"
OLD_TRACE_TO = "2026-03-25T16:00"

NUM_CLONED_SERVICES = 13                     # MC + 12 clones

results = []


def post_search(body, label=""):
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        API + "/search", data=data,
        headers={"Content-Type": "application/json"}
    )
    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
            return payload, time.time() - start
    except urllib.error.HTTPError as e:
        elapsed = time.time() - start
        try: body_text = e.read().decode()[:300]
        except: body_text = ""
        print(f"  HTTP ERROR {e.code}: {body_text}")
        return None, elapsed
    except Exception as e:
        return None, time.time() - start


def get_json(path):
    start = time.time()
    try:
        with urllib.request.urlopen(API + path, timeout=60) as resp:
            return json.loads(resp.read().decode("utf-8")), time.time() - start
    except Exception as e:
        print(f"  ERROR on GET {path}: {e}")
        return None, time.time() - start


def run_test(name, body, expect_fn=None, expected_behavior=None):
    """Run a single test. expected_behavior documents WHY a result is expected."""
    print(f"\n{'='*70}")
    print(f"TEST: {name}")
    print(f"{'='*70}")
    compact = {k: v for k, v in body.items() if v is not None and v != "" and v != []}
    print(f"  Request: {json.dumps(compact, indent=2)}")
    if expected_behavior:
        print(f"  Expected: {expected_behavior}")

    resp, elapsed = post_search(body, name)

    if resp is None:
        results.append({"name": name, "status": "FAIL", "time": elapsed, "error": "No response",
                         "expected": expected_behavior or ""})
        print(f"  RESULT: FAIL (no response, {elapsed:.2f}s)")
        return None

    total = resp.get("totalMatched", 0)
    structural = resp.get("filteredByStructured", 0)
    entries_count = len(resp.get("entries", []))
    strategy = resp.get("strategy", "?")
    next_offset = resp.get("nextOffsets")  # per-service offset map (kept for backward compat)
    search_id = resp.get("searchId")      # cache key for Load More
    sort_order = resp.get("sortOrder", "?")

    status = "PASS"
    notes = ""
    if expect_fn:
        try:
            msg = expect_fn(resp)
            if msg:
                status = "FAIL"
                notes = msg
        except Exception as e:
            status = "FAIL"
            notes = str(e)

    results.append({
        "name": name,
        "status": status,
        "time": round(elapsed, 3),
        "totalMatched": total,
        "filteredByStructured": structural,
        "entriesReturned": entries_count,
        "strategy": strategy,
        "sortOrder": sort_order,
        "hasNextOffset": next_offset is not None or search_id is not None,
        "notes": notes,
        "expected": expected_behavior or ""
    })

    print(f"  Strategy:     {strategy}")
    print(f"  Sort:         {sort_order}")
    print(f"  Structural:   {structural:,}")
    print(f"  Total match:  {total:,}")
    print(f"  Returned:     {entries_count}")
    print(f"  Next offset:  {'yes' if next_offset or search_id else 'no'}")
    print(f"  Search ID:    {search_id or '-'}")
    print(f"  Time:         {elapsed:.3f}s")
    print(f"  Status:       {status} {notes}")

    return resp


# =====================================================================
# PRE-FLIGHT
# =====================================================================
print("\n" + "="*70)
print("PRE-FLIGHT: Checking services")
print("="*70)

svc_resp, svc_time = get_json("/services")
if svc_resp:
    print(f"  Services found: {len(svc_resp)}")
    for s in svc_resp:
        exists = s.get("exists", False)
        size_mb = s.get("sizeKb", 0) / 1024
        print(f"    {s['name']:25s} exists={exists}  size={size_mb:,.1f} MB")
    print(f"  Time: {svc_time:.3f}s")
else:
    print("  FATAL: Cannot reach /services. Is the app running?")
    sys.exit(1)

service_count = len(svc_resp)
services_with_files = [s["name"] for s in svc_resp if s.get("exists")]
print(f"\n  Services with files: {len(services_with_files)}")

# =====================================================================
# CATEGORY 1: BASIC SCANNING (no filters)
# =====================================================================
print("\n\n" + "#"*70)
print("# CATEGORY 1: BASIC SCANNING")
print("#"*70)

run_test("T1: DESC no filter", {
    "sortOrder": "desc", "limit": 500
}, lambda r: f"Expected 500 entries (got {len(r.get('entries',[]))})" if len(r.get("entries",[])) != 500 else
   (f"Expected searchId for cache (got none)" if not r.get("searchId") else None),
   expected_behavior=f"Should return 500 from {NUM_CLONED_SERVICES} services, strategy=BACKWARD, with searchId")

run_test("T2: ASC no filter", {
    "sortOrder": "asc", "limit": 500
}, lambda r: f"Expected 500 entries (got {len(r.get('entries',[]))})" if len(r.get("entries",[])) != 500 else None,
   expected_behavior=f"Should return 500 from {NUM_CLONED_SERVICES} services, strategy=FORWARD")

# =====================================================================
# CATEGORY 2: TRACE ID & SPAN ID (RECENT — within 100K scan window)
# =====================================================================
print("\n\n" + "#"*70)
print("# CATEGORY 2: TRACE/SPAN (RECENT — within backward scan window)")
print("#"*70)

run_test("T3: DESC + recent traceId (no time)", {
    "traceId": RECENT_TRACE_ID, "sortOrder": "desc", "limit": 500
}, lambda r: f"recent traceId should find matches (got {r['totalMatched']})" if r["totalMatched"] == 0 else None,
   expected_behavior=f"~10 hits/file x {NUM_CLONED_SERVICES} services = ~130 matches. traceId at 03:36 is within 100K window.")

run_test("T4: ASC + recent traceId (no time)", {
    "traceId": RECENT_TRACE_ID, "sortOrder": "asc", "limit": 500
}, lambda r: f"ASC recent traceId should find matches (got {r['totalMatched']})" if r["totalMatched"] == 0 else None,
   expected_behavior="ASC+structural filter uses BACKWARD strategy (reads from end). Same data as T3.")

run_test("T5: DESC + recent spanId (no time)", {
    "spanId": RECENT_SPAN_ID, "sortOrder": "desc", "limit": 500
}, lambda r: f"recent spanId should find matches (got {r['totalMatched']})" if r["totalMatched"] == 0 else None,
   expected_behavior=f"spanId 1cd29a80... paired with traceId ffdef2f5... Same ~130 matches.")

run_test("T6: DESC + recent traceId + time range", {
    "traceId": RECENT_TRACE_ID,
    "from": "2026-03-26T03:30", "to": "2026-03-26T03:37",
    "sortOrder": "desc", "limit": 500
}, lambda r: f"traceId+time should find matches (got {r['totalMatched']})" if r["totalMatched"] == 0 else None,
   expected_behavior="Binary search jumps to 03:30, then backward scan. Same entries as T3.")

# =====================================================================
# CATEGORY 3: TRACE ID (OLD — outside 100K window, needs binary search)
# =====================================================================
print("\n\n" + "#"*70)
print("# CATEGORY 3: TRACE/SPAN (OLD — outside 100K window)")
print("#"*70)

run_test("T7: DESC + old traceId (NO time — expected 0)", {
    "traceId": OLD_TRACE_ID, "sortOrder": "desc", "limit": 500
}, expected_behavior="EXPECTED: 0 results. traceId is at 2026-03-25 14:57, ~12h before file end. "
   "BACKWARD scans last 100K lines covering only 00:24-03:37. Cannot reach. "
   "This is a known limitation: without time range, scan window is fixed at 100K lines from end.")

run_test("T8: DESC + old traceId + time range (should find)", {
    "traceId": OLD_TRACE_ID,
    "from": OLD_TRACE_FROM, "to": OLD_TRACE_TO,
    "sortOrder": "desc", "limit": 500
}, lambda r: f"old traceId WITH time should find matches (got {r['totalMatched']})" if r["totalMatched"] == 0 else None,
   expected_behavior=f"Binary search jumps to 14:00 position. ~33 hits/file x {NUM_CLONED_SERVICES} = ~429.")

run_test("T9: ASC + old traceId + time range (should find)", {
    "traceId": OLD_TRACE_ID,
    "from": OLD_TRACE_FROM, "to": OLD_TRACE_TO,
    "sortOrder": "asc", "limit": 500
}, lambda r: f"ASC old traceId WITH time should find (got {r['totalMatched']})" if r["totalMatched"] == 0 else None,
   expected_behavior="BINARY_THEN_FORWARD to 14:00, then forward scan. Same count as T8.")

run_test("T10: DESC + old spanId (NO time — expected 0)", {
    "spanId": OLD_SPAN_ID, "sortOrder": "desc", "limit": 500
}, expected_behavior="EXPECTED: 0 results. Same reason as T7 — spanId is outside 100K window.")

# =====================================================================
# CATEGORY 4: LEVEL FILTERS
# =====================================================================
print("\n\n" + "#"*70)
print("# CATEGORY 4: LEVEL FILTERS")
print("#"*70)

run_test("T11: DESC + level=DEBUG", {
    "level": "DEBUG", "sortOrder": "desc", "limit": 500
}, lambda r: "DEBUG should find matches" if r["totalMatched"] == 0 else None,
   expected_behavior=f"~68,205 DEBUG entries per file in 100K window x {NUM_CLONED_SERVICES} services.")

run_test("T12: DESC + level=INFO", {
    "level": "INFO", "sortOrder": "desc", "limit": 500
}, lambda r: "INFO should find matches" if r["totalMatched"] == 0 else None,
   expected_behavior=f"~31,768 INFO entries per file in 100K window x {NUM_CLONED_SERVICES} services.")

run_test("T13: DESC + level=WARN", {
    "level": "WARN", "sortOrder": "desc", "limit": 500
}, lambda r: "WARN should find matches" if r["totalMatched"] == 0 else None,
   expected_behavior=f"~27 WARN entries per file in 100K window x {NUM_CLONED_SERVICES} = ~351.")

run_test("T14: DESC + level=ERROR (expected 0 in scan window)", {
    "level": "ERROR", "sortOrder": "desc", "limit": 500
}, expected_behavior="EXPECTED: 0 results in BACKWARD scan. ERROR entries are only at file start "
   "(2026-03-18T13:04 — log4j appender errors during startup). "
   "100K backward scan from end covers only 00:24-03:37 on 03-26. No ERRORs there.")

run_test("T15: DESC + level=ERROR + historical time (finds startup errors)", {
    "level": "ERROR",
    "from": HISTORICAL_FROM, "to": HISTORICAL_TO,
    "sortOrder": "desc", "limit": 500
}, expected_behavior="BINARY_THEN_BACKWARD to 15:00, scan backward. Should find ERROR entries from startup at 13:04.")

# =====================================================================
# CATEGORY 5: USER/SITE/TENANT FILTERS
# =====================================================================
print("\n\n" + "#"*70)
print("# CATEGORY 5: USER/SITE/TENANT FILTERS")
print("#"*70)

run_test("T16: DESC + userId", {
    "userId": USER_ID, "sortOrder": "desc", "limit": 500
}, lambda r: "userId should find matches" if r["totalMatched"] == 0 else None,
   expected_behavior=f"~65,807 entries per file with userId=redsUser x {NUM_CLONED_SERVICES} services.")

run_test("T17: DESC + siteId", {
    "siteId": SITE_ID, "sortOrder": "desc", "limit": 500
}, lambda r: "siteId should find matches" if r["totalMatched"] == 0 else None,
   expected_behavior="Same entries as T16 (siteId and userId always appear together).")

run_test("T18: DESC + tenantId", {
    "tenantId": TENANT_ID, "sortOrder": "desc", "limit": 500
}, lambda r: "tenantId should find matches" if r["totalMatched"] == 0 else None,
   expected_behavior="Same entries as T16/T17.")

# =====================================================================
# CATEGORY 6: LOGGER & MESSAGE STRUCTURAL FILTERS
# =====================================================================
print("\n\n" + "#"*70)
print("# CATEGORY 6: LOGGER & MESSAGE FILTERS")
print("#"*70)

run_test("T19: DESC + logger filter", {
    "logger": LOGGER_FILTER, "sortOrder": "desc", "limit": 500
}, lambda r: "logger filter should find matches" if r["totalMatched"] == 0 else None,
   expected_behavior=f"ByteArrayStxEtxSerializer: ~36,700 hits/50MB, many in 100K window x {NUM_CLONED_SERVICES}.")

run_test("T20: DESC + message filter", {
    "message": MESSAGE_FILTER, "sortOrder": "desc", "limit": 500
}, lambda r: "message filter should find matches" if r["totalMatched"] == 0 else None,
   expected_behavior=f"'Deserialize': ~8,000 hits/50MB x {NUM_CLONED_SERVICES} services.")

# =====================================================================
# CATEGORY 7: TIME RANGE + BINARY SEARCH STRATEGIES
# =====================================================================
print("\n\n" + "#"*70)
print("# CATEGORY 7: TIME RANGE + BINARY SEARCH")
print("#"*70)

run_test("T21: DESC + recent time range", {
    "from": "2026-03-26T02:00", "to": "2026-03-26T03:30",
    "sortOrder": "desc", "limit": 500
}, expected_behavior="BINARY_THEN_BACKWARD: binary search to 03:30, backward scan to 02:00.")

run_test("T22: DESC + historical time range", {
    "from": HISTORICAL_FROM, "to": HISTORICAL_TO,
    "sortOrder": "desc", "limit": 500
}, expected_behavior="BINARY_THEN_BACKWARD: binary seeks to 15:00 position in 2.5GB file, then backward.")

run_test("T23: ASC + time range (BINARY_THEN_FORWARD)", {
    "from": HISTORICAL_FROM, "to": HISTORICAL_TO,
    "sortOrder": "asc", "limit": 500
}, expected_behavior="BINARY_THEN_FORWARD: binary seeks to 13:00, then forward scan to 15:00.")

# =====================================================================
# CATEGORY 8: QUERY ENGINE (Pass 2)
# =====================================================================
print("\n\n" + "#"*70)
print("# CATEGORY 8: QUERY ENGINE")
print("#"*70)

run_test("T24: Query keyword (Heartbeat)", {
    "query": QUERY_KEYWORD, "sortOrder": "desc", "limit": 500
}, lambda r: "query keyword should find matches" if r["totalMatched"] == 0 else None,
   expected_behavior="Pass 1: all 100K entries survive. Pass 2: filter by 'Heartbeat' substring.")

run_test("T25: Query boolean (level:WARN AND message:timeout)", {
    "query": "level:WARN AND message:timeout",
    "sortOrder": "desc", "limit": 500
}, expected_behavior="Pass 2 boolean: WARN entries with 'timeout' in message. ~27 WARN per file, subset with timeout.")

run_test("T26: Query field:value (logger:ByteArrayStxEtxSerializer)", {
    "query": "logger:ByteArrayStxEtxSerializer",
    "sortOrder": "desc", "limit": 500
}, expected_behavior="Pass 2 field match. Same results as T19 (structural logger) but via query engine.")

run_test("T27: Complex boolean ((level:INFO OR level:WARN) AND message:Heartbeat)", {
    "query": "(level:INFO OR level:WARN) AND message:Heartbeat",
    "sortOrder": "desc", "limit": 500
}, expected_behavior="Pass 2: INFO or WARN entries with Heartbeat in message.")

run_test("T28: Query NOT (NOT level:DEBUG)", {
    "query": "NOT level:DEBUG",
    "sortOrder": "desc", "limit": 500
}, expected_behavior="Pass 2: everything except DEBUG. Should match INFO + WARN entries.")

# =====================================================================
# CATEGORY 9: COMBINED FILTERS
# =====================================================================
print("\n\n" + "#"*70)
print("# CATEGORY 9: COMBINED FILTERS")
print("#"*70)

run_test("T29: traceId + userId + time", {
    "traceId": OLD_TRACE_ID, "userId": USER_ID,
    "from": OLD_TRACE_FROM, "to": OLD_TRACE_TO,
    "sortOrder": "desc", "limit": 500
}, lambda r: "combined should find matches" if r["totalMatched"] == 0 else None,
   expected_behavior="Binary to 16:00, backward. traceId AND userId must both match.")

run_test("T30: level=INFO + logger + message", {
    "level": "INFO", "logger": LOGGER_FILTER, "message": "Message Received",
    "sortOrder": "desc", "limit": 500
}, expected_behavior="Structural: INFO AND logger=ByteArray... AND message contains 'Message Received'.")

run_test("T31: userId + level=WARN (structural) + query", {
    "level": "WARN", "query": "Heartbeat",
    "sortOrder": "desc", "limit": 500
}, expected_behavior="Pass 1: WARN entries. Pass 2: Heartbeat keyword. All WARN entries have 'Heartbeat timeout'.")

# =====================================================================
# CATEGORY 10: PAGINATION
# =====================================================================
print("\n\n" + "#"*70)
print("# CATEGORY 10: PAGINATION")
print("#"*70)

page1 = run_test("T32a: Page 1 (DESC no filter)", {
    "sortOrder": "desc", "limit": 500
}, expected_behavior="First page, should have searchId for Load More.")

if page1 and page1.get("searchId"):
    page2 = run_test("T32b: Page 2 (Load More from cache)", {
        "sortOrder": "desc", "limit": 500,
        "searchId": page1["searchId"]
    }, lambda r: f"Expected 500 entries (got {len(r.get('entries',[]))})" if len(r.get("entries",[])) != 500 else None,
       expected_behavior="Cache hit: serves entries 500-999 from cached result. Should be near-instant (0 disk I/O).")

    # Verify no overlap between page 1 and page 2
    if page2:
        p1_times = set(e.get("timestamp", "") for e in page1.get("entries", []))
        p2_times = set(e.get("timestamp", "") for e in page2.get("entries", []))
        overlap = p1_times & p2_times
        print(f"  Page overlap check: {len(overlap)} shared timestamps (some overlap on same-second entries is OK)")

# =====================================================================
# CATEGORY 11: LIMITS & STRESS
# =====================================================================
print("\n\n" + "#"*70)
print("# CATEGORY 11: LIMITS & STRESS")
print("#"*70)

run_test("T33: Single service only", {
    "services": ["momentumconnect"],
    "sortOrder": "desc", "limit": 500
}, expected_behavior="Only 1 service scanned instead of 13. Should be ~3-5x faster.")

run_test("T34: Large limit=5000", {
    "sortOrder": "desc", "limit": 5000
}, expected_behavior="Returns 5000 entries from merged 13-service results.")

run_test("T35: Limit=0 'All' (safeCap=50000)", {
    "sortOrder": "desc", "limit": 0
}, expected_behavior="Backend caps at 50,000 entries. Tests memory under heavy load.")

# =====================================================================
# CATEGORY 12: CACHE VERIFICATION
# =====================================================================
print("\n\n" + "#"*70)
print("# CATEGORY 12: CACHE VERIFICATION")
print("#"*70)

# T35 just ran with fingerprint "all-desc" — T36 should hit cache instantly
run_test("T36: Cache reuse (same fingerprint as T35)", {
    "sortOrder": "desc", "limit": 500
}, lambda r: f"Expected 500 entries (got {len(r.get('entries',[]))})" if len(r.get("entries",[])) != 500 else None,
   expected_behavior="Same structural fingerprint as T35 (all-desc). Should be cache hit, near-instant.")

# Query-from-cache: structural fingerprint unchanged, only query differs
run_test("T37: Query from cache (Deserialize)", {
    "query": "Deserialize", "sortOrder": "desc", "limit": 500
}, lambda r: f"Query-from-cache: expected matches (got {r['totalMatched']})" if r["totalMatched"] == 0 else None,
   expected_behavior="Fingerprint = all-desc (cached). Query applied on cached 100K structural entries. Zero disk I/O.")

# Different query on same cache — verify cache Layer 1 survives query changes
run_test("T38: Second query from cache (Heartbeat)", {
    "query": "Heartbeat", "sortOrder": "desc", "limit": 500
}, lambda r: f"Second query-from-cache: expected matches (got {r['totalMatched']})" if r["totalMatched"] == 0 else None,
   expected_behavior="Same cache, different query. Should filter from structural Layer 1 again. Zero disk I/O.")

# =====================================================================
# CATEGORY 13: SORT ORDER VERIFICATION
# =====================================================================
print("\n\n" + "#"*70)
print("# CATEGORY 13: SORT ORDER VERIFICATION")
print("#"*70)

def check_desc_order(resp):
    entries = resp.get("entries", [])
    if len(entries) < 2:
        return None
    for i in range(len(entries) - 1):
        t1 = entries[i].get("timestamp")
        t2 = entries[i+1].get("timestamp")
        if t1 and t2 and t1 < t2:
            return f"DESC order violated at index {i}: {t1} < {t2}"
    return None

def check_asc_order(resp):
    entries = resp.get("entries", [])
    if len(entries) < 2:
        return None
    for i in range(len(entries) - 1):
        t1 = entries[i].get("timestamp")
        t2 = entries[i+1].get("timestamp")
        if t1 and t2 and t1 > t2:
            return f"ASC order violated at index {i}: {t1} > {t2}"
    return None

run_test("T39: DESC sort order correctness", {
    "sortOrder": "desc", "limit": 500
}, check_desc_order,
   expected_behavior="Verify entries are strictly in descending timestamp order.")

run_test("T40: ASC sort order correctness", {
    "sortOrder": "asc", "limit": 500
}, check_asc_order,
   expected_behavior="Verify entries are strictly in ascending timestamp order.")

# DESC + level=DEBUG (many entries, likely exercises bounded heap eviction)
run_test("T41: DESC level=DEBUG sort order", {
    "level": "DEBUG", "sortOrder": "desc", "limit": 500
}, check_desc_order,
   expected_behavior="887K structural entries -- bounded heap evicts ~787K. Verify remaining 100K sorted correctly.")

# ASC + time range (exercises BINARY_THEN_FORWARD + bounded heap for ASC)
run_test("T42: ASC time range sort order", {
    "from": HISTORICAL_FROM, "to": HISTORICAL_TO,
    "sortOrder": "asc", "limit": 500
}, check_asc_order,
   expected_behavior="BINARY_THEN_FORWARD + ASC bounded heap. Verify correct sort after heap drain.")

# =====================================================================
# CATEGORY 14: MULTI-VALUE FILTERS
# =====================================================================
print("\n\n" + "#"*70)
print("# CATEGORY 14: MULTI-VALUE FILTERS")
print("#"*70)

run_test("T43: Multi-traceId (comma-separated)", {
    "traceId": f"{RECENT_TRACE_ID},{OLD_TRACE_ID}",
    "from": OLD_TRACE_FROM, "to": FILE_END,
    "sortOrder": "desc", "limit": 500
}, lambda r: f"Multi-traceId should find matches (got {r['totalMatched']})" if r["totalMatched"] == 0 else None,
   expected_behavior=f"Two traceIds with OR logic. Should find ~130 (recent) + ~429 (old) = ~559 matches across {NUM_CLONED_SERVICES} services.")

run_test("T44: Multi-spanId (comma-separated)", {
    "spanId": f"{RECENT_SPAN_ID},{OLD_SPAN_ID}",
    "from": OLD_TRACE_FROM, "to": FILE_END,
    "sortOrder": "desc", "limit": 500
}, lambda r: f"Multi-spanId should find matches (got {r['totalMatched']})" if r["totalMatched"] == 0 else None,
   expected_behavior="Two spanIds with OR logic. Same hits as T43 (trace/span are paired).")

# =====================================================================
# CATEGORY 15: DEEP PAGINATION & CACHE CLEAR
# =====================================================================
print("\n\n" + "#"*70)
print("# CATEGORY 15: DEEP PAGINATION & CACHE CLEAR")
print("#"*70)

# Deep pagination: get 3 consecutive pages, verify no overlap
dp1 = run_test("T45a: Deep pagination page 1", {
    "sortOrder": "desc", "limit": 500
}, lambda r: f"Expected 500 entries (got {len(r.get('entries',[]))})" if len(r.get("entries",[])) != 500 else None,
   expected_behavior="Page 1 of deep pagination test. Should return exactly 500 entries.")

if dp1 and dp1.get("searchId"):
    dp2 = run_test("T45b: Deep pagination page 2", {
        "sortOrder": "desc", "limit": 500,
        "searchId": dp1["searchId"]
    }, lambda r: f"Expected 500 entries (got {len(r.get('entries',[]))})" if len(r.get("entries",[])) != 500 else None,
       expected_behavior="Page 2 from cache. Should return exactly 500 entries, no disk I/O.")

    if dp2 and dp2.get("searchId"):
        dp3 = run_test("T45c: Deep pagination page 3", {
            "sortOrder": "desc", "limit": 500,
            "searchId": dp2["searchId"]
        }, lambda r: f"Expected 500 entries (got {len(r.get('entries',[]))})" if len(r.get("entries",[])) != 500 else None,
           expected_behavior="Page 3 from cache. Verifies cursor tracks correctly across 3 pages.")

        # Verify no overlap across all 3 pages
        if dp3:
            p1_ts = [e.get("timestamp","") for e in dp1.get("entries",[])]
            p2_ts = [e.get("timestamp","") for e in dp2.get("entries",[])]
            p3_ts = [e.get("timestamp","") for e in dp3.get("entries",[])]
            # Check DESC ordering across pages: last of page N >= first of page N+1
            if p1_ts and p2_ts and p1_ts[-1] and p2_ts[0]:
                if p1_ts[-1] < p2_ts[0]:
                    print(f"  WARNING: Page 1 last ({p1_ts[-1]}) < Page 2 first ({p2_ts[0]}) — cross-page order broken")
                else:
                    print(f"  Cross-page order OK: p1_last={p1_ts[-1]} >= p2_first={p2_ts[0]}")
            if p2_ts and p3_ts and p2_ts[-1] and p3_ts[0]:
                if p2_ts[-1] < p3_ts[0]:
                    print(f"  WARNING: Page 2 last ({p2_ts[-1]}) < Page 3 first ({p3_ts[0]}) — cross-page order broken")
                else:
                    print(f"  Cross-page order OK: p2_last={p2_ts[-1]} >= p3_first={p3_ts[0]}")

# Cache clear test
print("\n  --- Clearing cache via API ---")
try:
    clear_req = urllib.request.Request(API + "/cache/clear", method="POST")
    with urllib.request.urlopen(clear_req, timeout=10) as resp:
        print(f"  Cache clear: HTTP {resp.status}")
except Exception as e:
    print(f"  Cache clear failed: {e}")

# After clearing, next search must do a fresh scan (should be slower)
run_test("T46: Post-cache-clear fresh scan", {
    "sortOrder": "desc", "limit": 500
}, lambda r: f"Expected matches (got {r['totalMatched']})" if r["totalMatched"] == 0 else None,
   expected_behavior="Cache was just cleared. This must be a fresh disk scan — validates cache clear works.")

# =====================================================================
# CATEGORY 16: ASC + QUERY
# =====================================================================
print("\n\n" + "#"*70)
print("# CATEGORY 16: ASC + QUERY")
print("#"*70)

run_test("T47: ASC + query (Heartbeat)", {
    "query": "Heartbeat", "sortOrder": "asc", "limit": 500
}, lambda r: f"ASC query should find matches (got {r['totalMatched']})" if r["totalMatched"] == 0 else None,
   expected_behavior="ASC with Pass 2 query. Tests ASC bounded heap + query filter from cache.")

run_test("T48: ASC + query from cache (Deserialize)", {
    "query": "Deserialize", "sortOrder": "asc", "limit": 500
}, lambda r: f"ASC query from cache should find matches (got {r['totalMatched']})" if r["totalMatched"] == 0 else None,
   expected_behavior="Same ASC structural fingerprint as T47, different query. Should be cache hit.")


# =====================================================================
# RESULTS SUMMARY
# =====================================================================

print("\n\n")
print("="*130)
print(f"LOAD TEST RESULTS - {service_count} Services (MC + clones), all 2.5 GB each")
print("="*130)

# Group by category
categories = {}
for r in results:
    cat = r["name"].split(":")[0] if ":" in r["name"] else "Other"
    categories.setdefault(cat, []).append(r)

print(f"\n{'#':<5} {'Test Name':<58} {'Status':<7} {'Time':>8} {'Matched':>10} {'Structural':>12} {'Strategy':<25}")
print("-"*130)

total_time = 0
pass_count = 0
fail_count = 0

for i, r in enumerate(results, 1):
    total_time += r.get("time", 0)
    if r["status"] == "PASS":
        pass_count += 1
    else:
        fail_count += 1

    matched = r.get("totalMatched", "-")
    structural = r.get("filteredByStructured", "-")
    strategy = r.get("strategy", "-")

    matched_str = f"{matched:,}" if isinstance(matched, int) else str(matched)
    structural_str = f"{structural:,}" if isinstance(structural, int) else str(structural)

    print(f"{i:<5} {r['name']:<58} {r['status']:<7} {r['time']:>7.3f}s {matched_str:>10} {structural_str:>12} {strategy:<25}")

print("-"*130)
print(f"\nTotal tests: {len(results)}  |  Passed: {pass_count}  |  Failed: {fail_count}  |  Total time: {total_time:.2f}s")
print(f"Services: {service_count}  |  Services with files: {len(services_with_files)}")

# Performance summary
times = [r.get("time", 0) for r in results]
if times:
    print(f"\nPERFORMANCE:")
    print(f"  Fastest:  {min(times):.3f}s")
    print(f"  Slowest:  {max(times):.3f}s")
    print(f"  Average:  {sum(times)/len(times):.3f}s")

# Highlight failures
if fail_count > 0:
    print(f"\nFAILED TESTS:")
    for r in results:
        if r["status"] == "FAIL":
            note = r.get("notes", "")
            exp = r.get("expected", "")
            print(f"  {r['name']}: {note}")
            if exp:
                print(f"    Expected behavior: {exp}")

# Expected behaviors (0-result tests that are NOT failures)
expected_zero = [r for r in results if r.get("totalMatched", -1) == 0 and r["status"] == "PASS"]
if expected_zero:
    print(f"\nEXPECTED 0-RESULT TESTS (by design):")
    for r in expected_zero:
        print(f"  {r['name']}")
        if r.get("expected"):
            print(f"    Reason: {r['expected']}")

# Slow tests
slow = [r for r in results if r.get("time", 0) > 5]
if slow:
    print(f"\nSLOW TESTS (>5s):")
    for r in slow:
        print(f"  {r['name']}: {r['time']:.3f}s")

print("\n" + "="*130)
