package com.honeywell.loglens.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.honeywell.loglens.config.LogLensConfig;
import com.honeywell.loglens.model.SearchRequest;
import com.honeywell.loglens.model.ServiceConfig;
import com.honeywell.loglens.service.LogSearchService.SearchResult;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for multi-user cache behavior in LogSearchService.
 *
 * <p>Uses real LogSearchService with temp log files — no mocks. Verifies that the two-layer cache
 * (structural + per-session views) correctly handles concurrent users, shared caches, isolated
 * cursors, eviction, and cache clearing.
 */
class MultiUserCacheIntegrationTest {

  @TempDir Path tempDir;

  private LogSearchService searchService;
  private LogLensConfig config;
  private LogParserService parser;
  private QueryEngine queryEngine;

  @BeforeEach
  void setUp() throws IOException {
    // Write 50 log entries in plain Format B to a temp file
    Path logFile = tempDir.resolve("test-service.log");
    Files.writeString(logFile, generateLogLines(50));

    // Configure a single service pointing at the temp log file
    ServiceConfig svc = new ServiceConfig();
    svc.setName("test-service");
    svc.setLogFile(logFile.toString());
    svc.setColor("#1a6fa3");

    config = new LogLensConfig();
    config.setServices(List.of(svc));
    config.setTailLines(500);
    config.setMaxScanLines(100_000);
    config.setScanPoolSize(2);

    parser = new LogParserService();
    queryEngine = new QueryEngine();
    searchService = new LogSearchService(config, parser, queryEngine);
  }

  // ── Test 1: Three users, same structural filters → shared cache ──────────

  @Test
  void threeUsers_sameSearch_sharedStructuralCache() {
    SearchRequest req1 = makeRequest(null);
    SearchRequest req2 = makeRequest(null);
    SearchRequest req3 = makeRequest(null);

    SearchResult r1 = searchService.search(req1);
    SearchResult r2 = searchService.search(req2);
    SearchResult r3 = searchService.search(req3);

    // All three should get valid results
    assertThat(r1.entries()).isNotEmpty();
    assertThat(r2.entries()).isNotEmpty();
    assertThat(r3.entries()).isNotEmpty();

    // Each gets a different searchId (own session)
    Set<String> searchIds = new HashSet<>();
    if (r1.searchId() != null) searchIds.add(r1.searchId());
    if (r2.searchId() != null) searchIds.add(r2.searchId());
    if (r3.searchId() != null) searchIds.add(r3.searchId());
    // If all returned searchIds (have more pages), they must be distinct
    // With limit=10 and 50 entries, all should have searchIds
    assertThat(searchIds).hasSize(3);

    // cacheStore should have exactly 1 structural cache (shared fingerprint)
    assertThat(getCacheStoreSize()).isEqualTo(1);
  }

  // ── Test 2: Two users, different queries → isolated views ────────────────

  @Test
  void twoUsers_differentQueries_isolatedViews() {
    // User A: no query → gets all entries
    SearchRequest reqA = makeRequest(null);
    SearchResult rA = searchService.search(reqA);

    // User B: query="ERROR" → gets only ERROR entries
    SearchRequest reqB = makeRequest("ERROR");
    SearchResult rB = searchService.search(reqB);

    // User A sees all 50 entries total
    assertThat(rA.filteredByStructured()).isEqualTo(50);
    assertThat(rA.totalMatched()).isEqualTo(50);

    // User B sees subset — only ERROR entries (every 5th line = 10 ERRORs)
    assertThat(rB.filteredByStructured()).isEqualTo(50); // structural is same
    assertThat(rB.totalMatched()).isEqualTo(10); // query filtered to ERROR only

    // Both page sizes are bounded by limit=10
    assertThat(rA.entries()).hasSize(10);
    assertThat(rB.entries()).hasSize(10);

    // User B's entries should all be ERROR level
    rB.entries().forEach(e -> assertThat(e.getLevel()).isEqualTo("ERROR"));

    // Shared structural cache
    assertThat(getCacheStoreSize()).isEqualTo(1);
  }

  // ── Test 3: Load More from two tabs — interleaved pagination ─────────────

  @Test
  void loadMore_fromTwoTabs_interleavedPagination() {
    // User A: first search → page 1 (entries 0-9 in DESC order)
    SearchRequest reqA = makeRequest(null);
    SearchResult rA1 = searchService.search(reqA);
    assertThat(rA1.entries()).hasSize(10);
    assertThat(rA1.searchId()).isNotNull();

    // User B: first search → page 1 (also entries 0-9 in DESC order)
    SearchRequest reqB = makeRequest(null);
    SearchResult rB1 = searchService.search(reqB);
    assertThat(rB1.entries()).hasSize(10);
    assertThat(rB1.searchId()).isNotNull();

    // Different sessions
    assertThat(rA1.searchId()).isNotEqualTo(rB1.searchId());

    // User A: Load More → page 2 (entries 10-19)
    SearchRequest loadMoreA = new SearchRequest();
    loadMoreA.setSearchId(rA1.searchId());
    loadMoreA.setLimit(10);
    SearchResult rA2 = searchService.search(loadMoreA);
    assertThat(rA2.entries()).hasSize(10);

    // User A's page 2 should NOT overlap with User B's page 1
    Set<String> aPage2Messages = new HashSet<>();
    rA2.entries().forEach(e -> aPage2Messages.add(e.getMessage()));

    Set<String> bPage1Messages = new HashSet<>();
    rB1.entries().forEach(e -> bPage1Messages.add(e.getMessage()));

    // Pages should have no overlap — different cursor positions
    assertThat(aPage2Messages).doesNotContainAnyElementsOf(bPage1Messages);

    // Also verify A's page 1 and page 2 don't overlap
    Set<String> aPage1Messages = new HashSet<>();
    rA1.entries().forEach(e -> aPage1Messages.add(e.getMessage()));
    assertThat(aPage2Messages).doesNotContainAnyElementsOf(aPage1Messages);
  }

  // ── Test 4: 10 concurrent threads — no corruption ───────────────────────

  @Test
  void concurrentSearches_tenThreads_noCorruption() throws Exception {
    int threadCount = 10;
    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    CopyOnWriteArrayList<SearchResult> results = new CopyOnWriteArrayList<>();
    CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    for (int i = 0; i < threadCount; i++) {
      pool.submit(
          () -> {
            try {
              startGate.await(); // all threads start at once
              SearchRequest req = makeRequest(null);
              SearchResult result = searchService.search(req);
              results.add(result);
            } catch (Throwable t) {
              errors.add(t);
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startGate.countDown(); // release all threads
    boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
    pool.shutdown();
    pool.awaitTermination(5, TimeUnit.SECONDS);

    assertThat(finished).as("All threads should complete within 30s").isTrue();
    assertThat(errors).as("No exceptions should be thrown").isEmpty();
    assertThat(results).hasSize(threadCount);

    // Every result must have valid entries
    for (SearchResult r : results) {
      assertThat(r.entries()).isNotEmpty();
      assertThat(r.totalMatched()).isEqualTo(50);
      assertThat(r.entries()).hasSizeLessThanOrEqualTo(10);
    }

    // All should share the same structural cache
    assertThat(getCacheStoreSize()).isEqualTo(1);
  }

  // ── Test 5: Search → clear → search again → fresh scan ─────────────────

  @Test
  void searchThenClear_nextSearchScansFromDisk() {
    // First search
    SearchRequest req1 = makeRequest(null);
    SearchResult r1 = searchService.search(req1);
    assertThat(r1.entries()).hasSize(10);
    String searchId1 = r1.searchId();
    assertThat(searchId1).isNotNull();

    // Clear all caches
    searchService.clearAllCaches();
    assertThat(getCacheStoreSize()).isZero();

    // Search again — must scan from disk, get a new searchId
    SearchRequest req2 = makeRequest(null);
    SearchResult r2 = searchService.search(req2);
    assertThat(r2.entries()).hasSize(10);
    String searchId2 = r2.searchId();
    assertThat(searchId2).isNotNull();

    // searchIds must differ (different sessions from different caches)
    assertThat(searchId2).isNotEqualTo(searchId1);

    // Old searchId should no longer work — falls back to disk scan
    SearchRequest loadOld = new SearchRequest();
    loadOld.setSearchId(searchId1);
    loadOld.setLimit(10);
    loadOld.setSortOrder("desc");
    SearchResult rOld = searchService.search(loadOld);
    // Falls back to disk scan (no cache hit) — still returns results
    assertThat(rOld.entries()).isNotEmpty();
    // The searchId in response should be new (not the old one)
    if (rOld.searchId() != null) {
      assertThat(rOld.searchId()).isNotEqualTo(searchId1);
    }
  }

  // ── Test 6: Session capacity — 11 sessions, oldest evicted ──────────────

  @Test
  void sessionCapacity_elevenSessions_oldestEvicted() {
    // Create 11 sessions on the same structural cache.
    // MAX_SESSIONS_PER_CACHE = 10, so the oldest should get evicted.
    List<String> searchIds = new ArrayList<>();
    for (int i = 0; i < 11; i++) {
      SearchRequest req = makeRequest(null);
      SearchResult r = searchService.search(req);
      if (r.searchId() != null) {
        searchIds.add(r.searchId());
      }
      if (i == 0) {
        // Ensure session 0 has a distinctly older lastAccessed than the rest,
        // so evictOldestSession() deterministically picks it.
        try {
          Thread.sleep(15);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError("Interrupted while preparing session eviction order", e);
        }
      }
    }

    // Should have created 11 searchIds
    assertThat(searchIds).hasSize(11);

    // Still only 1 structural cache
    assertThat(getCacheStoreSize()).isEqualTo(1);

    // Session count should not exceed MAX_SESSIONS_PER_CACHE (10)
    int sessionCount = getSessionCount();
    assertThat(sessionCount).isLessThanOrEqualTo(10);

    // The oldest (first) searchId should have been evicted
    SearchRequest loadFirst = new SearchRequest();
    loadFirst.setSearchId(searchIds.get(0));
    SearchResult rFirst = searchService.search(loadFirst);
    // If evicted, the Load More falls back to a fresh disk scan — the returned
    // searchId will be different from the original
    if (rFirst.searchId() != null) {
      assertThat(rFirst.searchId()).isNotEqualTo(searchIds.get(0));
    }

    // The latest (11th) searchId should still work via cache
    SearchRequest loadLast = new SearchRequest();
    loadLast.setSearchId(searchIds.get(10));
    SearchResult rLast = searchService.search(loadLast);
    assertThat(rLast.entries()).isNotEmpty();
  }

  // ── Helper methods ──────────────────────────────────────────────────────

  /**
   * Generates log lines in plain Format B (Log4j2) with incrementing timestamps. Every 5th line is
   * ERROR, the rest are INFO. 3 rotating users.
   */
  private String generateLogLines(int count) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < count; i++) {
      String ts = String.format("2026-03-10 21:%02d:%02d.%03d", i / 3600, (i / 60) % 60, i % 1000);
      String level = (i % 5 == 0) ? "ERROR" : "INFO";
      sb.append(
          String.format(
              "%s host1 %s [user%d,site1,tenant1] [app,trace%d,span%d] 12345 --- [main] com.test.Logger : Message %d%n",
              ts, level, i % 3, i, i, i));
    }
    return sb.toString();
  }

  /** Creates a standard SearchRequest with limit=10, DESC order, and optional query. */
  private SearchRequest makeRequest(String query) {
    SearchRequest req = new SearchRequest();
    req.setServices(List.of("test-service"));
    req.setLimit(10);
    req.setSortOrder("desc");
    if (query != null) {
      req.setQuery(query);
    }
    return req;
  }

  /** Reflectively reads cacheStore.size() from LogSearchService. */
  @SuppressWarnings("unchecked")
  private int getCacheStoreSize() {
    try {
      Field f = LogSearchService.class.getDeclaredField("cacheStore");
      f.setAccessible(true);
      ConcurrentHashMap<?, ?> store = (ConcurrentHashMap<?, ?>) f.get(searchService);
      return store.size();
    } catch (Exception e) {
      throw new RuntimeException("Failed to read cacheStore via reflection", e);
    }
  }

  /** Reflectively reads the session count of the first (and only) structural cache. */
  @SuppressWarnings("unchecked")
  private int getSessionCount() {
    try {
      Field f = LogSearchService.class.getDeclaredField("cacheStore");
      f.setAccessible(true);
      ConcurrentHashMap<String, ?> store = (ConcurrentHashMap<String, ?>) f.get(searchService);
      if (store.isEmpty()) return 0;
      Object cache = store.values().iterator().next();
      Field sessionsField = cache.getClass().getDeclaredField("sessions");
      sessionsField.setAccessible(true);
      ConcurrentHashMap<?, ?> sessions = (ConcurrentHashMap<?, ?>) sessionsField.get(cache);
      return sessions.size();
    } catch (Exception e) {
      throw new RuntimeException("Failed to read sessions via reflection", e);
    }
  }
}
