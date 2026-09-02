"""
Quick stress test to spike LogLens metrics bar.
Simulates 5 concurrent users doing rapid searches with varied filters.
"""
import json, time, sys, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8090/loglens"
API = BASE + "/api/logs"

SEARCHES = [
    {"limit": 500, "sortOrder": "desc"},
    {"limit": 500, "sortOrder": "asc"},
    {"level": "ERROR", "limit": 500},
    {"level": "DEBUG", "limit": 500},
    {"level": "INFO", "limit": 1000},
    {"level": "WARN", "limit": 500},
    {"query": "Exception", "limit": 500},
    {"query": "timeout OR error", "limit": 500},
    {"query": "NOT debug", "limit": 500},
    {"services": ["routing"], "limit": 500},
    {"services": ["print"], "limit": 500},
    {"services": ["momentumconnect"], "limit": 1000},
    {"services": ["routing", "print"], "limit": 500},
    {"message": "Heartbeat", "limit": 500},
    {"limit": 2000, "sortOrder": "desc"},
    {"limit": 2000, "sortOrder": "asc"},
    {"query": "error AND NOT debug", "limit": 500},
    {"level": "INFO", "sortOrder": "asc", "limit": 1000},
    {"level": "DEBUG", "sortOrder": "asc", "limit": 1000},
    {"limit": 5000, "sortOrder": "desc"},
]

def do_search(body, user_id, req_num):
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(API + "/search", data=data,
        headers={"Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
            elapsed = time.time() - t0
            total = payload.get("totalMatched", 0)
            entries = len(payload.get("entries", []))
            search_id = payload.get("searchId")
            # If there's a searchId, do a Load More
            if search_id:
                load_more = {"searchId": search_id, "limit": 500}
                do_search(load_more, user_id, f"{req_num}-LM")
            return f"  User{user_id} req#{req_num}: {entries} entries, {total} total, {elapsed:.2f}s"
    except Exception as e:
        return f"  User{user_id} req#{req_num}: ERROR {e}"

def simulate_user(user_id):
    results = []
    for i, search in enumerate(SEARCHES):
        result = do_search(search, user_id, i+1)
        results.append(result)
    return results

print(f"\n{'='*60}")
print(f"  STRESS TEST — 5 concurrent users × {len(SEARCHES)} searches each")
print(f"  Target: {BASE}")
print(f"{'='*60}\n")

# Check health first
try:
    with urllib.request.urlopen(API + "/health", timeout=5) as r:
        print(f"  Health: {json.loads(r.read().decode())}\n")
except:
    print("  ERROR: App not reachable!\n")
    sys.exit(1)

t_start = time.time()

with ThreadPoolExecutor(max_workers=5) as pool:
    futures = {pool.submit(simulate_user, uid): uid for uid in range(1, 6)}
    for f in as_completed(futures):
        uid = futures[f]
        try:
            for line in f.result():
                print(line)
        except Exception as e:
            print(f"  User{uid}: CRASHED — {e}")

total_time = time.time() - t_start
total_reqs = 5 * len(SEARCHES)

print(f"\n{'='*60}")
print(f"  DONE: {total_reqs}+ requests in {total_time:.1f}s")
print(f"  (~{total_reqs/total_time:.0f} req/s)")
print(f"{'='*60}\n")

# Show final metrics
try:
    with urllib.request.urlopen(API + "/metrics", timeout=5) as r:
        metrics = json.loads(r.read().decode())
        print("  Final Metrics:")
        for k, v in metrics.items():
            print(f"    {k}: {v}")
except Exception as e:
    print(f"  Could not fetch metrics: {e}")
