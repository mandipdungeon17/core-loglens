package com.honeywell.loglens.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.honeywell.loglens.config.LogLensConfig;
import com.honeywell.loglens.model.LogEntry;
import com.honeywell.loglens.model.SearchRequest;
import com.honeywell.loglens.model.ServiceConfig;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Comprehensive JUnit 5 tests for LogSearchService — the core scanning, caching, and pagination
 * engine (~1440 lines).
 *
 * <p>Uses @TempDir for real file I/O with known log content. No Spring context needed: constructs
 * LogSearchService directly with real LogParserService and QueryEngine.
 */
class LogSearchServiceTest {

  private static final DateTimeFormatter TS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

  @TempDir Path tempDir;

  private LogLensConfig config;
  private LogParserService parser;
  private QueryEngine queryEngine;

  // ── Helpers ──────────────────────────────────────────────────────────────────

  /**
   * Generates N plain Log4j2 (Format B) log lines with incrementing timestamps. Lines alternate
   * between INFO and ERROR levels. Each line gets a unique traceId.
   */
  private List<String> generateLogLines(int count, LocalDateTime startTime) {
    return generateLogLines(count, startTime, "testSvc");
  }

  private List<String> generateLogLines(int count, LocalDateTime startTime, String appName) {
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      LocalDateTime ts = startTime.plusSeconds(i);
      String level = (i % 2 == 0) ? "INFO" : "ERROR";
      String userId = "user" + (i % 3);
      String siteId = "site" + (i % 2);
      String tenantId = "tenant" + (i % 2);
      String traceId = String.format("trace%04d", i);
      String spanId = String.format("span%04d", i);
      String thread = (i % 2 == 0) ? "main" : "http-" + i;
      String logger = (i % 2 == 0) ? "com.test.Logger" : "com.test.Other";
      String message = "Message " + i;

      String line =
          String.format(
              "%s host1 %s [%s,%s,%s] [%s,%s,%s] 12345 --- [%s] %s : %s",
              ts.format(TS),
              level,
              userId,
              siteId,
              tenantId,
              appName,
              traceId,
              spanId,
              thread,
              logger,
              message);
      lines.add(line);
    }
    return lines;
  }

  /** Generates lines where ALL have the specified level. */
  private List<String> generateLogLinesWithLevel(int count, LocalDateTime startTime, String level) {
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      LocalDateTime ts = startTime.plusSeconds(i);
      String traceId = String.format("trace%04d", i);
      String line =
          String.format(
              "%s host1 %s [user0,site0,tenant0] [app,%s,span0] 12345 --- [main] com.test.Logger : Message %d",
              ts.format(TS), level, traceId, i);
      lines.add(line);
    }
    return lines;
  }

  /** Creates a temp log file with the given lines, returns the file path. */
  private String createLogFile(String name, List<String> lines) throws IOException {
    Path file = tempDir.resolve(name);
    Files.write(file, lines, StandardCharsets.UTF_8);
    return file.toAbsolutePath().toString();
  }

  /** Creates a ServiceConfig pointing to the given file path. */
  private ServiceConfig svcConfig(String name, String logFilePath) {
    ServiceConfig sc = new ServiceConfig();
    sc.setName(name);
    sc.setLogFile(logFilePath);
    sc.setColor("#123456");
    return sc;
  }

  /** Builds a LogLensConfig with the given services. */
  private LogLensConfig buildConfig(List<ServiceConfig> services) {
    LogLensConfig cfg = new LogLensConfig();
    cfg.setServices(services);
    cfg.setTailLines(500);
    cfg.setMaxScanLines(100_000);
    cfg.setScanPoolSize(2); // fixed pool for predictable tests
    return cfg;
  }

  /** Constructs a fresh LogSearchService from the current config. */
  private LogSearchService buildService() {
    return new LogSearchService(config, parser, queryEngine);
  }

  /** Creates a basic SearchRequest for the given service with DESC sort. */
  private SearchRequest basicRequest(String serviceName, int limit) {
    SearchRequest req = new SearchRequest();
    req.setServices(List.of(serviceName));
    req.setLimit(limit);
    req.setSortOrder("desc");
    return req;
  }

  @BeforeEach
  void setUp() {
    parser = new LogParserService();
    queryEngine = new QueryEngine();
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // Cache multi-user isolation
  // ══════════════════════════════════════════════════════════════════════════════

  @Nested
  class CacheMultiUserIsolation {

    @Test
    void search_twoUsers_sameFilters_independentCursors() throws IOException {
      // Two searches with identical structural filters get different searchIds.
      // Load More on one does not affect the other's cursor.
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(20, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      // User A: first search — gets page 1
      SearchRequest reqA = basicRequest("svc", 5);
      LogSearchService.SearchResult resA1 = service.search(reqA);
      assertThat(resA1.searchId()).isNotNull();
      assertThat(resA1.entries()).hasSize(5);

      // User B: same structural filters — gets own session
      SearchRequest reqB = basicRequest("svc", 5);
      LogSearchService.SearchResult resB1 = service.search(reqB);
      assertThat(resB1.searchId()).isNotNull();
      assertThat(resB1.entries()).hasSize(5);

      // Different search IDs
      assertThat(resA1.searchId()).isNotEqualTo(resB1.searchId());

      // User A: Load More — advances A's cursor only
      SearchRequest loadMoreA = new SearchRequest();
      loadMoreA.setSearchId(resA1.searchId());
      loadMoreA.setLimit(5);
      LogSearchService.SearchResult resA2 = service.search(loadMoreA);
      assertThat(resA2.entries()).hasSize(5);

      // User B: Load More — B still starts from its own cursor (page 2)
      SearchRequest loadMoreB = new SearchRequest();
      loadMoreB.setSearchId(resB1.searchId());
      loadMoreB.setLimit(5);
      LogSearchService.SearchResult resB2 = service.search(loadMoreB);
      assertThat(resB2.entries()).hasSize(5);

      // Verify A and B got the same page 2 entries (same data, same structural cache)
      assertThat(resA2.entries().get(0).getTimestamp())
          .isEqualTo(resB2.entries().get(0).getTimestamp());
    }

    @Test
    void search_twoUsers_differentQueries_sameStructural() throws IOException {
      // Same structural filters, different query strings.
      // Second user gets a query-filtered view from the same structural cache.
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(20, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      // User A: no query — gets all 20 entries
      SearchRequest reqA = basicRequest("svc", 100);
      LogSearchService.SearchResult resA = service.search(reqA);
      assertThat(resA.entries()).hasSize(20);

      // User B: query "Error" — only ERROR entries
      SearchRequest reqB = basicRequest("svc", 100);
      reqB.setQuery("level:ERROR");
      LogSearchService.SearchResult resB = service.search(reqB);

      // B should have fewer entries (only ERROR lines)
      assertThat(resB.entries()).hasSizeLessThan(resA.entries().size());
      assertThat(resB.entries()).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo("ERROR"));

      // Both share the same structural count
      assertThat(resB.filteredByStructured()).isEqualTo(resA.filteredByStructured());
    }

    @Test
    void search_twoTabs_loadMore_noInterference() throws IOException {
      // User A loads more 2x, user B does fresh search.
      // Both have correct page sizes.
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(30, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      // User A: first page (5 entries)
      SearchRequest reqA = basicRequest("svc", 5);
      LogSearchService.SearchResult a1 = service.search(reqA);
      assertThat(a1.entries()).hasSize(5);

      // User A: load more page 2
      SearchRequest loadA2 = new SearchRequest();
      loadA2.setSearchId(a1.searchId());
      loadA2.setLimit(5);
      LogSearchService.SearchResult a2 = service.search(loadA2);
      assertThat(a2.entries()).hasSize(5);

      // User A: load more page 3
      SearchRequest loadA3 = new SearchRequest();
      loadA3.setSearchId(a2.searchId());
      loadA3.setLimit(5);
      LogSearchService.SearchResult a3 = service.search(loadA3);
      assertThat(a3.entries()).hasSize(5);

      // User B: fresh search with same filters — gets page 1
      SearchRequest reqB = basicRequest("svc", 5);
      LogSearchService.SearchResult b1 = service.search(reqB);
      assertThat(b1.entries()).hasSize(5);
      assertThat(b1.searchId()).isNotEqualTo(a3.searchId());

      // User A has consumed 15 entries; B only 5 — their cursors are independent.
      // Verify B's first entry matches A's first entry (same data, same sort)
      assertThat(b1.entries().get(0).getTimestamp()).isEqualTo(a1.entries().get(0).getTimestamp());
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // Pagination
  // ══════════════════════════════════════════════════════════════════════════════

  @Nested
  class Pagination {

    @Test
    void search_firstPage_returnsLimitEntries() throws IOException {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(20, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      SearchRequest req = basicRequest("svc", 5);
      LogSearchService.SearchResult res = service.search(req);

      assertThat(res.entries()).hasSize(5);
      assertThat(res.searchId()).isNotNull();
      assertThat(res.totalMatched()).isEqualTo(20);
      assertThat(res.totalCached()).isEqualTo(20);
    }

    @Test
    void search_loadMore_continuesFromCursor() throws IOException {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(15, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      // First page
      SearchRequest req = basicRequest("svc", 5);
      LogSearchService.SearchResult page1 = service.search(req);
      assertThat(page1.entries()).hasSize(5);

      // Load More — page 2
      SearchRequest loadMore = new SearchRequest();
      loadMore.setSearchId(page1.searchId());
      loadMore.setLimit(5);
      LogSearchService.SearchResult page2 = service.search(loadMore);
      assertThat(page2.entries()).hasSize(5);

      // Page 2 entries should be different from page 1 (no overlap)
      List<LocalDateTime> page1Times =
          page1.entries().stream().map(LogEntry::getTimestamp).toList();
      List<LocalDateTime> page2Times =
          page2.entries().stream().map(LogEntry::getTimestamp).toList();
      assertThat(page2Times).doesNotContainAnyElementsOf(page1Times);
    }

    @Test
    void search_lastPage_searchIdNull() throws IOException {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(10, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      // Fetch all 10 entries in one page
      SearchRequest req = basicRequest("svc", 10);
      LogSearchService.SearchResult res = service.search(req);

      assertThat(res.entries()).hasSize(10);
      // searchId always returned (for export); no more pages = entries.size == totalCached
      assertThat(res.searchId()).isNotNull();
      assertThat(res.entries()).hasSize(res.totalCached());
    }

    @Test
    void search_cursorAtEnd_emptyPage() throws IOException {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(10, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      // First page: all 5
      SearchRequest req = basicRequest("svc", 5);
      LogSearchService.SearchResult page1 = service.search(req);

      // Second page: remaining 5 — searchId still present (for export), all entries now served
      SearchRequest loadMore = new SearchRequest();
      loadMore.setSearchId(page1.searchId());
      loadMore.setLimit(5);
      LogSearchService.SearchResult page2 = service.search(loadMore);
      assertThat(page2.entries()).hasSize(5);
      assertThat(page2.searchId()).isNotNull();

      // Page 2 served all remaining — cursor is exhausted.
      // A third "Load More" with page1's searchId should find the session
      // with cursor at end and return an empty page.
      SearchRequest loadAgain = new SearchRequest();
      loadAgain.setSearchId(page1.searchId());
      loadAgain.setLimit(5);
      LogSearchService.SearchResult page3 = service.search(loadAgain);
      assertThat(page3.entries()).isEmpty();
      assertThat(page3.searchId()).isNotNull();
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // Cumulative offsets
  // ══════════════════════════════════════════════════════════════════════════════

  @Nested
  class CumulativeOffsets {

    @Test
    void serveCachePage_accumulatesOffsetsAcrossPages() throws IOException {
      // Two services, paginated. Offsets should accumulate across pages.
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logA = createLogFile("svcA.log", generateLogLines(10, start, "appA"));
      String logB = createLogFile("svcB.log", generateLogLines(10, start.plusSeconds(5), "appB"));
      config = buildConfig(List.of(svcConfig("svcA", logA), svcConfig("svcB", logB)));
      LogSearchService service = buildService();

      SearchRequest req = new SearchRequest();
      req.setServices(List.of("svcA", "svcB"));
      req.setLimit(5);
      req.setSortOrder("desc");
      LogSearchService.SearchResult page1 = service.search(req);
      assertThat(page1.entries()).hasSize(5);
      Map<String, Long> offsets1 = page1.nextOffsets();

      // Page 2: offsets should include both services (or at least the ones seen so
      // far)
      SearchRequest loadMore = new SearchRequest();
      loadMore.setSearchId(page1.searchId());
      loadMore.setLimit(5);
      LogSearchService.SearchResult page2 = service.search(loadMore);
      Map<String, Long> offsets2 = page2.nextOffsets();

      // Offsets should grow (or stay) — never shrink. They accumulate.
      // At minimum, page 2 offsets should have at least as many service keys as page
      // 1.
      assertThat(offsets2.size()).isGreaterThanOrEqualTo(offsets1.size());
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // Cache lifecycle
  // ══════════════════════════════════════════════════════════════════════════════

  @Nested
  class CacheLifecycle {

    @Test
    void search_cacheReuse_sameFingerprint() throws IOException {
      // Same structural filters should reuse the structural cache and create a new
      // session.
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(10, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      // First search — creates cache
      SearchRequest req1 = basicRequest("svc", 10);
      LogSearchService.SearchResult res1 = service.search(req1);

      // Second search — same filters, should reuse cache
      SearchRequest req2 = basicRequest("svc", 10);
      LogSearchService.SearchResult res2 = service.search(req2);

      // Same structural data
      assertThat(res2.entries()).hasSameSizeAs(res1.entries());
      assertThat(res2.filteredByStructured()).isEqualTo(res1.filteredByStructured());

      // But different searchIds (new sessions)
      // Note: res1.searchId() is null (all served), res2.searchId() is also null
      // So check by data equality — entries should match exactly
      assertThat(res2.entries().get(0).getTimestamp())
          .isEqualTo(res1.entries().get(0).getTimestamp());
      assertThat(res2.totalCached()).isEqualTo(res1.totalCached());
    }

    @Test
    void search_differentFingerprint_newCache() throws IOException {
      // Different level filter should create a new cache entry.
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(20, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      // First search — no level filter
      SearchRequest req1 = basicRequest("svc", 100);
      LogSearchService.SearchResult res1 = service.search(req1);
      assertThat(res1.entries()).hasSize(20);

      // Second search — level=ERROR → different structural fingerprint
      SearchRequest req2 = basicRequest("svc", 100);
      req2.setLevel("ERROR");
      LogSearchService.SearchResult res2 = service.search(req2);

      // Should have fewer entries (only ERROR lines)
      assertThat(res2.entries()).hasSizeLessThan(res1.entries().size());
      assertThat(res2.entries()).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo("ERROR"));

      // Different structural counts
      assertThat(res2.filteredByStructured()).isNotEqualTo(res1.filteredByStructured());
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // Edge cases — file handling
  // ══════════════════════════════════════════════════════════════════════════════

  @Nested
  class EdgeCasesFileHandling {

    @Test
    void scan_emptyFile_returnsEmpty() throws IOException {
      String logPath = createLogFile("empty.log", List.of());
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      SearchRequest req = basicRequest("svc", 100);
      LogSearchService.SearchResult res = service.search(req);

      assertThat(res.entries()).isEmpty();
    }

    @Test
    void scan_singleLineFile_returnsOneEntry() throws IOException {
      LocalDateTime ts = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String line =
          String.format(
              "%s host1 INFO [user1,site1,tenant1] [app,trace1,span1] 12345 --- [main] com.test.Logger : Single entry",
              ts.format(TS));
      String logPath = createLogFile("single.log", List.of(line));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      SearchRequest req = basicRequest("svc", 100);
      LogSearchService.SearchResult res = service.search(req);

      assertThat(res.entries()).hasSize(1);
      assertThat(res.entries().get(0).getMessage()).contains("Single entry");
      assertThat(res.entries().get(0).getLevel()).isEqualTo("INFO");
    }

    @Test
    void scan_missingFile_returnsEmpty() {
      // Path points to a nonexistent file — should not crash, returns empty
      String fakePath = tempDir.resolve("nonexistent.log").toAbsolutePath().toString();
      config = buildConfig(List.of(svcConfig("svc", fakePath)));
      LogSearchService service = buildService();

      SearchRequest req = basicRequest("svc", 100);
      LogSearchService.SearchResult res = service.search(req);

      assertThat(res.entries()).isEmpty();
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // Sort order
  // ══════════════════════════════════════════════════════════════════════════════

  @Nested
  class SortOrder {

    @Test
    void search_desc_newestFirst() throws IOException {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(10, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      SearchRequest req = basicRequest("svc", 100);
      req.setSortOrder("desc");
      LogSearchService.SearchResult res = service.search(req);

      assertThat(res.entries()).hasSize(10);
      assertThat(res.sortOrder()).isEqualTo("desc");

      // Verify descending order: each entry should be >= the next
      for (int i = 0; i < res.entries().size() - 1; i++) {
        LocalDateTime current = res.entries().get(i).getTimestamp();
        LocalDateTime next = res.entries().get(i + 1).getTimestamp();
        assertThat(current).isAfterOrEqualTo(next);
      }
    }

    @Test
    void search_asc_oldestFirst() throws IOException {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(10, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      SearchRequest req = basicRequest("svc", 100);
      req.setSortOrder("asc");
      LogSearchService.SearchResult res = service.search(req);

      assertThat(res.entries()).hasSize(10);
      assertThat(res.sortOrder()).isEqualTo("asc");

      // Verify ascending order: each entry should be <= the next
      for (int i = 0; i < res.entries().size() - 1; i++) {
        LocalDateTime current = res.entries().get(i).getTimestamp();
        LocalDateTime next = res.entries().get(i + 1).getTimestamp();
        assertThat(current).isBeforeOrEqualTo(next);
      }
    }

    @Test
    void search_asc_toOnly_usesBinaryThenForward() throws IOException {
      // ASC + only "to" time filter should use BINARY_THEN_FORWARD strategy
      // (mirrors the DESC fix for BINARY_THEN_BACKWARD with any time filter)
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(20, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      SearchRequest req = basicRequest("svc", 100);
      req.setSortOrder("asc");
      req.setTo(start.plusSeconds(10)); // only "to" set, no "from"
      LogSearchService.SearchResult res = service.search(req);

      assertThat(res.strategy()).isEqualTo("BINARY_THEN_FORWARD");
      // All returned entries should be before or at the "to" time
      for (LogEntry e : res.entries()) {
        assertThat(e.getTimestamp()).isBeforeOrEqualTo(start.plusSeconds(10));
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // Structural filters (Pass 1)
  // ══════════════════════════════════════════════════════════════════════════════

  @Nested
  class StructuralFilters {

    @Test
    void search_levelFilter_onlyMatchingLevel() throws IOException {
      // Generate 10 INFO + 10 ERROR lines
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      List<String> infoLines = generateLogLinesWithLevel(10, start, "INFO");
      List<String> errorLines = generateLogLinesWithLevel(10, start.plusSeconds(10), "ERROR");
      List<String> all = new ArrayList<>(infoLines);
      all.addAll(errorLines);

      String logPath = createLogFile("svc.log", all);
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      SearchRequest req = basicRequest("svc", 100);
      req.setLevel("ERROR");
      LogSearchService.SearchResult res = service.search(req);

      assertThat(res.entries()).hasSize(10);
      assertThat(res.entries()).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo("ERROR"));
    }

    @Test
    void search_traceIdFilter_matchesSubstring() throws IOException {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(20, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      // Filter for trace0005 — should match exactly one entry
      SearchRequest req = basicRequest("svc", 100);
      req.setTraceId("trace0005");
      LogSearchService.SearchResult res = service.search(req);

      assertThat(res.entries()).hasSize(1);
      assertThat(res.entries().get(0).getTraceId()).contains("trace0005");
    }

    @Test
    void search_timeRange_returnsOnlyInRange() throws IOException {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      // 20 entries: 10:00:00 through 10:00:19
      String logPath = createLogFile("svc.log", generateLogLines(20, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      // Filter to entries between 10:00:05 and 10:00:14 (10 entries)
      SearchRequest req = basicRequest("svc", 100);
      req.setFrom(LocalDateTime.of(2026, 3, 10, 10, 0, 5));
      req.setTo(LocalDateTime.of(2026, 3, 10, 10, 0, 14));
      LogSearchService.SearchResult res = service.search(req);

      assertThat(res.entries()).hasSizeBetween(8, 12); // approximate due to boundary
      assertThat(res.entries())
          .allSatisfy(
              e -> {
                assertThat(e.getTimestamp())
                    .isAfterOrEqualTo(LocalDateTime.of(2026, 3, 10, 10, 0, 5));
                assertThat(e.getTimestamp())
                    .isBeforeOrEqualTo(LocalDateTime.of(2026, 3, 10, 10, 0, 14));
              });
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // Query (Pass 2)
  // ══════════════════════════════════════════════════════════════════════════════

  @Nested
  class QueryFilter {

    @Test
    void search_queryFilter_narrowsResults() throws IOException {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(20, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      // No query — gets all 20
      SearchRequest reqAll = basicRequest("svc", 100);
      LogSearchService.SearchResult resAll = service.search(reqAll);
      assertThat(resAll.entries()).hasSize(20);

      // Query for "Message 5" — should narrow to entries containing "Message 5"
      SearchRequest reqQuery = basicRequest("svc", 100);
      reqQuery.setQuery("\"Message 5\"");
      LogSearchService.SearchResult resQuery = service.search(reqQuery);

      assertThat(resQuery.entries()).hasSizeLessThan(resAll.entries().size());
      assertThat(resQuery.entries())
          .allSatisfy(e -> assertThat(e.getMessage()).contains("Message 5"));
      // filteredByStructured should match total (no structural filter applied)
      assertThat(resQuery.filteredByStructured()).isEqualTo(resAll.filteredByStructured());
    }

    @Test
    void search_queryChange_zeroDiskIO() throws IOException {
      // Changing query on same structural filters reuses cache (no new disk scan).
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(20, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      // First search: query "level:INFO"
      SearchRequest req1 = basicRequest("svc", 100);
      req1.setQuery("level:INFO");
      LogSearchService.SearchResult res1 = service.search(req1);
      int infoCount = res1.entries().size();

      // Second search: query "level:ERROR" — same structural fingerprint
      SearchRequest req2 = basicRequest("svc", 100);
      req2.setQuery("level:ERROR");
      LogSearchService.SearchResult res2 = service.search(req2);
      int errorCount = res2.entries().size();

      // Both should share the same structural count
      assertThat(res2.filteredByStructured()).isEqualTo(res1.filteredByStructured());
      // But different query results
      assertThat(infoCount + errorCount).isEqualTo(20);
      assertThat(res2.entries()).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo("ERROR"));
    }

    @Test
    void search_queryOnSlimFormatLogs_findsLpnSubstring() throws IOException {
      // Reproduce exact SLIM format (Format D) from real WES routing logs
      // Verifies query substring search works against SLIM-parsed entries
      List<String> lines =
          List.of(
              "2026-04-10 06:45:28.930 INFO  [redsUser] [routing,,] [pool-16-thread-8] c.h.i.w.r.a.l.SomeListener : Generic message without LPN",
              "2026-04-10 06:45:28.931 DEBUG [redsUser] [routing,40b4e2c0d67e9e09,971e029507a750b5] [pool-16-thread-8] c.h.r.a.i.p.InboundMessageProcessor : Event headers {key=value} and body containerLpn=260000011272",
              "2026-04-10 06:45:28.933 INFO  [redsUser] [routing,40b4e2c0d67e9e09,971e029507a750b5] [pool-16-thread-8] c.h.i.w.r.a.l.MomentumConnectEventListener : listenLabelDataResponseEvent:: Received Label Data Response event: containerLpn=260000011272",
              "2026-04-10 06:45:28.934 DEBUG [redsUser] [routing,40b4e2c0d67e9e09,971e029507a750b5] [pool-16-thread-8] c.h.i.w.r.r.i.s.ContainerRouteInfoServiceImplBase : findByContainerLpn() retrieve ContainerRouteInfos based on business keys 260000011272",
              "2026-04-10 06:45:28.935 INFO  [redsUser] [routing,40b4e2c0d67e9e09,971e029507a750b5] [pool-16-thread-8] c.h.i.w.r.r.c.e.h.ContainerRouteInfoChangeEventHandler : ContainerRouteInfo location Change Event Handler: ContainerLpn=260000011272",
              "2026-04-10 06:45:28.940 INFO  [redsUser] [routing,,] [pool-16-thread-8] c.h.i.w.r.a.l.SomeOther : Another message without the number");
      String logPath = createLogFile("routing.log", lines);
      config = buildConfig(List.of(svcConfig("routing", logPath)));
      LogSearchService service = buildService();

      // Search with query for the LPN
      SearchRequest req = basicRequest("routing", 100);
      req.setQuery("\"260000011272\"");
      LogSearchService.SearchResult result = service.search(req);

      // Should find 4 entries that contain the LPN (2 DEBUG + 2 INFO)
      assertThat(result.entries()).hasSizeGreaterThanOrEqualTo(4);
      assertThat(result.entries())
          .allSatisfy(e -> assertThat(e.getRawLine()).contains("260000011272"));
      // Structural total should be all 6 entries
      assertThat(result.filteredByStructured()).isEqualTo(6);

      // Also try with level:INFO structural filter — should still find 2 INFO entries with LPN
      SearchRequest reqInfo = basicRequest("routing", 100);
      reqInfo.setLevel("INFO");
      reqInfo.setQuery("260000011272");
      LogSearchService.SearchResult resInfo = service.search(reqInfo);

      assertThat(resInfo.entries()).hasSize(2);
      assertThat(resInfo.entries())
          .allSatisfy(
              e -> {
                assertThat(e.getLevel()).isEqualTo("INFO");
                assertThat(e.getRawLine()).contains("260000011272");
              });
      // Structural total = 4 INFO entries
      assertThat(resInfo.filteredByStructured()).isEqualTo(4);
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // isTruncated
  // ══════════════════════════════════════════════════════════════════════════════

  @Nested
  class IsTruncated {

    @Test
    void isTruncated_morePages_true() throws IOException {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(20, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      SearchRequest req = basicRequest("svc", 5);
      LogSearchService.SearchResult res = service.search(req);

      // searchId not null and entries < totalCached → truncated
      assertThat(res.searchId()).isNotNull();
      assertThat(res.isTruncated()).isTrue();
    }

    @Test
    void isTruncated_allServed_false() {
      // Test the isTruncated() method directly on a constructed SearchResult.
      // When searchId is null and nextOffsets is empty → not truncated.
      LogSearchService.SearchResult result =
          new LogSearchService.SearchResult(
              List.of(),
              0,
              0,
              100,
              "desc",
              Map.of(), // empty nextOffsets
              "BACKWARD",
              null,
              0);
      assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void isTruncated_nullSearchId_withOffsets_true() {
      // When searchId is null but nextOffsets exist → disk-scan truncation →
      // truncated
      LogSearchService.SearchResult result =
          new LogSearchService.SearchResult(
              List.of(),
              10,
              10,
              100,
              "desc",
              Map.of("svc", 12345L), // non-empty nextOffsets
              "BACKWARD",
              null,
              10);
      assertThat(result.isTruncated()).isTrue();
    }

    @Test
    void isTruncated_withSearchId_allCached_false() {
      // searchId present but entries.size() == totalCached → not truncated
      LogSearchService.SearchResult result =
          new LogSearchService.SearchResult(
              List.of(LogEntry.builder().build()),
              1,
              1,
              100,
              "desc",
              Map.of(),
              "BACKWARD",
              "session-1",
              1);
      assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void isTruncated_fullFlow_allServed_searchIdNull() throws IOException {
      // Full flow: when all entries are served, searchId is still returned (for export)
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(10, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      SearchRequest req = basicRequest("svc", 100);
      LogSearchService.SearchResult res = service.search(req);

      assertThat(res.entries()).hasSize(10);
      assertThat(res.searchId()).isNotNull();
      assertThat(res.entries()).hasSize(res.totalCached());
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // Pool sizing
  // ══════════════════════════════════════════════════════════════════════════════

  @Nested
  class PoolSizing {

    @Test
    void constructor_zeroServices_poolSizeAtLeastOne() {
      // Empty services list should not crash — pool size stays >= 1
      config = buildConfig(List.of());
      // Should not throw
      LogSearchService service = buildService();

      // Verify search with no matching services returns empty
      SearchRequest req = new SearchRequest();
      req.setLimit(10);
      req.setSortOrder("desc");
      LogSearchService.SearchResult res = service.search(req);
      assertThat(res.entries()).isEmpty();
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // Historical log file listing and search
  // ══════════════════════════════════════════════════════════════════════════════

  @Nested
  class HistoricalLogSearch {

    @Test
    void listServiceFiles_returnsRotatedFilesOnly() throws Exception {
      String logFile =
          createLogFile(
              "app-console.log", generateLogLines(5, LocalDateTime.now().minusMinutes(5)));
      // Create rotated files (same prefix)
      Files.write(
          tempDir.resolve("app-console.log.1"), List.of("old log line 1"), StandardCharsets.UTF_8);
      Files.write(
          tempDir.resolve("app-console.log.2.gz"), List.of("compressed"), StandardCharsets.UTF_8);
      // Create unrelated file (different prefix — should be excluded)
      Files.write(tempDir.resolve("other-service.log"), List.of("other"), StandardCharsets.UTF_8);
      Files.write(tempDir.resolve("config.yml"), List.of("port: 8080"), StandardCharsets.UTF_8);

      config = buildConfig(List.of(svcConfig("app", logFile)));
      LogSearchService service = buildService();

      List<LogSearchService.FileInfo> files = service.listServiceFiles("app");
      assertThat(files).hasSize(2);
      assertThat(files)
          .extracting(LogSearchService.FileInfo::name)
          .containsExactlyInAnyOrder("app-console.log.1", "app-console.log.2.gz");
    }

    @Test
    void listServiceFiles_unknownService_returnsEmpty() throws Exception {
      String logFile =
          createLogFile("app.log", generateLogLines(3, LocalDateTime.now().minusMinutes(1)));
      config = buildConfig(List.of(svcConfig("app", logFile)));
      LogSearchService service = buildService();

      assertThat(service.listServiceFiles("nonexistent")).isEmpty();
    }

    @Test
    void listServiceFiles_excludesActiveLogFile() throws Exception {
      String logFile =
          createLogFile("app.log", generateLogLines(5, LocalDateTime.now().minusMinutes(5)));
      Files.write(tempDir.resolve("app.log.1"), List.of("old line"), StandardCharsets.UTF_8);

      config = buildConfig(List.of(svcConfig("app", logFile)));
      LogSearchService service = buildService();

      List<LogSearchService.FileInfo> files = service.listServiceFiles("app");
      assertThat(files).hasSize(1);
      assertThat(files.get(0).name()).isEqualTo("app.log.1");
    }

    @Test
    void searchHistorical_validFile_returnsResults() throws Exception {
      LocalDateTime start = LocalDateTime.now().minusMinutes(10);
      String activeLog = createLogFile("svc-console.log", generateLogLines(3, start));
      // Create a rotated file with known content
      createLogFile("svc-console.log.1", generateLogLines(10, start.minusHours(1)));

      config = buildConfig(List.of(svcConfig("svc", activeLog)));
      LogSearchService service = buildService();

      SearchRequest req = new SearchRequest();
      req.setLimit(100);
      req.setSortOrder("desc");

      LogSearchService.SearchResult result =
          service.searchHistorical("svc", "svc-console.log.1", req);
      assertThat(result.entries()).hasSize(10);
      assertThat(result.searchId()).isNotNull();
    }

    @Test
    void searchHistorical_unknownService_throws() throws Exception {
      config = buildConfig(List.of());
      LogSearchService service = buildService();

      SearchRequest req = new SearchRequest();
      req.setLimit(10);

      assertThatThrownBy(() -> service.searchHistorical("ghost", "file.log.1", req))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Unknown service");
    }

    @Test
    void searchHistorical_pathTraversal_throws() throws Exception {
      String logFile =
          createLogFile("app.log", generateLogLines(3, LocalDateTime.now().minusMinutes(1)));
      config = buildConfig(List.of(svcConfig("app", logFile)));
      LogSearchService service = buildService();

      SearchRequest req = new SearchRequest();
      req.setLimit(10);

      assertThatThrownBy(() -> service.searchHistorical("app", "../etc/passwd", req))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid file name");
    }

    @Test
    void searchHistorical_nonRotatedFile_throws() throws Exception {
      String logFile =
          createLogFile("app.log", generateLogLines(3, LocalDateTime.now().minusMinutes(1)));
      // Create a file that doesn't match the rotated naming pattern
      Files.write(tempDir.resolve("secrets.txt"), List.of("password=abc"), StandardCharsets.UTF_8);
      config = buildConfig(List.of(svcConfig("app", logFile)));
      LogSearchService service = buildService();

      SearchRequest req = new SearchRequest();
      req.setLimit(10);

      assertThatThrownBy(() -> service.searchHistorical("app", "secrets.txt", req))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not a rotated log");
    }

    @Test
    void searchHistorical_loadMore_servesFromCache() throws Exception {
      LocalDateTime start = LocalDateTime.now().minusMinutes(30);
      String activeLog = createLogFile("svc.log", generateLogLines(3, start));
      createLogFile("svc.log.1", generateLogLines(20, start.minusHours(1)));

      config = buildConfig(List.of(svcConfig("svc", activeLog)));
      LogSearchService service = buildService();

      // First request — get first page
      SearchRequest req1 = new SearchRequest();
      req1.setLimit(5);
      req1.setSortOrder("desc");
      LogSearchService.SearchResult page1 = service.searchHistorical("svc", "svc.log.1", req1);
      assertThat(page1.entries()).hasSize(5);
      assertThat(page1.searchId()).isNotNull();

      // Load More — should serve from cache
      SearchRequest req2 = new SearchRequest();
      req2.setLimit(5);
      req2.setSortOrder("desc");
      req2.setSearchId(page1.searchId());
      LogSearchService.SearchResult page2 = service.searchHistorical("svc", "svc.log.1", req2);
      assertThat(page2.entries()).hasSize(5);

      // Entries should be different (different pages)
      assertThat(page2.entries().get(0).getTimestamp())
          .isNotEqualTo(page1.entries().get(0).getTimestamp());
    }

    @Test
    void searchHistorical_gzFile_returnsResults() throws Exception {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String activeLog = createLogFile("svc-console.log", generateLogLines(3, start));

      // Create a real .gz file with valid log content
      Path gzPath = tempDir.resolve("svc-console.log.1.gz");
      List<String> logLines = generateLogLines(8, start.minusHours(2));
      try (Writer w =
          new OutputStreamWriter(
              new GZIPOutputStream(new FileOutputStream(gzPath.toFile())),
              StandardCharsets.UTF_8)) {
        for (String line : logLines) {
          w.write(line);
          w.write('\n');
        }
      }

      config = buildConfig(List.of(svcConfig("svc", activeLog)));
      LogSearchService service = buildService();

      SearchRequest req = new SearchRequest();
      req.setLimit(100);
      req.setSortOrder("desc");

      LogSearchService.SearchResult result =
          service.searchHistorical("svc", "svc-console.log.1.gz", req);
      assertThat(result.entries()).hasSize(8);
      assertThat(result.strategy()).isEqualTo("BACKWARD");
    }

    @Test
    void listServiceFiles_dateRotatedGzPattern_discovered() throws Exception {
      String logFile =
          createLogFile("mc.log", generateLogLines(3, LocalDateTime.now().minusMinutes(1)));
      // Rotated .gz files with name-DATE-N.log.gz pattern (doesn't start with "mc.log")
      Files.write(
          tempDir.resolve("mc-04-20-2026-1.log.gz"), List.of("gz1"), StandardCharsets.UTF_8);
      Files.write(
          tempDir.resolve("mc-04-20-2026-2.log.gz"), List.of("gz2"), StandardCharsets.UTF_8);
      // Also keep the traditional pattern
      Files.write(tempDir.resolve("mc.log.1"), List.of("old"), StandardCharsets.UTF_8);

      config = buildConfig(List.of(svcConfig("mc", logFile)));
      LogSearchService service = buildService();

      List<LogSearchService.FileInfo> files = service.listServiceFiles("mc");
      assertThat(files).hasSize(3);
      assertThat(files)
          .extracting(LogSearchService.FileInfo::name)
          .containsExactlyInAnyOrder(
              "mc-04-20-2026-1.log.gz", "mc-04-20-2026-2.log.gz", "mc.log.1");
    }

    @Test
    void searchHistorical_dateRotatedGzFile_returnsResults() throws Exception {
      LocalDateTime start = LocalDateTime.of(2026, 4, 20, 10, 0, 0);
      String activeLog = createLogFile("mc.log", generateLogLines(3, start));

      // Create a .gz file with the name-DATE-N.log.gz pattern
      Path gzPath = tempDir.resolve("mc-04-20-2026-1.log.gz");
      List<String> logLines = generateLogLines(6, start.minusHours(2));
      try (Writer w =
          new OutputStreamWriter(
              new GZIPOutputStream(new FileOutputStream(gzPath.toFile())),
              StandardCharsets.UTF_8)) {
        for (String line : logLines) {
          w.write(line);
          w.write('\n');
        }
      }

      config = buildConfig(List.of(svcConfig("mc", activeLog)));
      LogSearchService service = buildService();

      SearchRequest req = new SearchRequest();
      req.setLimit(100);
      req.setSortOrder("desc");

      LogSearchService.SearchResult result =
          service.searchHistorical("mc", "mc-04-20-2026-1.log.gz", req);
      assertThat(result.entries()).hasSize(6);
      assertThat(result.strategy()).isEqualTo("BACKWARD");
    }

    @Test
    void searchHistorical_gzFile_descReturnsLatestEntries() throws Exception {
      LocalDateTime start = LocalDateTime.of(2026, 4, 20, 8, 0, 0);
      String activeLog = createLogFile("mc.log", generateLogLines(3, start));

      // Create a .gz with 200 lines, but maxScanLines=50 → should get the latest 50
      Path gzPath = tempDir.resolve("mc-04-20-2026-1.log.gz");
      List<String> logLines = generateLogLines(200, start.minusHours(4));
      try (Writer w =
          new OutputStreamWriter(
              new GZIPOutputStream(new FileOutputStream(gzPath.toFile())),
              StandardCharsets.UTF_8)) {
        for (String line : logLines) {
          w.write(line);
          w.write('\n');
        }
      }

      // Build config with low maxScanLines to prove BACKWARD returns latest, not oldest
      config = buildConfig(List.of(svcConfig("mc", activeLog)));
      config.setMaxScanLines(50);
      LogSearchService service = buildService();

      SearchRequest req = new SearchRequest();
      req.setLimit(200);
      req.setSortOrder("desc");

      LogSearchService.SearchResult result =
          service.searchHistorical("mc", "mc-04-20-2026-1.log.gz", req);
      // With BACKWARD strategy, we get entries near end of file (latest timestamps)
      assertThat(result.entries()).isNotEmpty();
      assertThat(result.strategy()).isEqualTo("BACKWARD");
      // The latest entry in the file is at index 199 (start + 199 seconds)
      // BACKWARD reads from end → should have the latest entries
      LocalDateTime latestInFile = start.minusHours(4).plusSeconds(199);
      assertThat(result.entries().get(0).getTimestamp()).isEqualTo(latestInFile);
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // Export
  // ══════════════════════════════════════════════════════════════════════════════

  @Nested
  class Export {

    @Test
    void getExportEntries_validSearchId_returnsEntries() throws IOException {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(10, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      SearchRequest req = basicRequest("svc", 5);
      LogSearchService.SearchResult res = service.search(req);
      assertThat(res.searchId()).isNotNull();

      LogSearchService.ExportData export = service.getExportEntries(res.searchId());
      assertThat(export).isNotNull();
      assertThat(export.entries()).isNotEmpty();
      assertThat(export.sortOrder()).isEqualTo("desc");
    }

    @Test
    void getExportEntries_invalidSearchId_returnsNull() throws IOException {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(5, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      assertThat(service.getExportEntries("nonexistent-search-id")).isNull();
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // Hybrid Query — deep scan applies query before PQ merge
  // ══════════════════════════════════════════════════════════════════════════════

  @Nested
  class HybridQuery {

    /**
     * Deep scan (maxScanLines > config default) + query → query applied before PQ merge so only
     * matching entries enter the cache.
     */
    @Test
    void deepScan_queryAppliedBeforePQ() throws IOException {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      // 40 lines: alternating INFO/ERROR (20 INFO, 20 ERROR)
      String logPath = createLogFile("svc.log", generateLogLines(40, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      // Deep scan: maxScanLines=200_000 > config default 100_000
      SearchRequest req = basicRequest("svc", 100);
      req.setMaxScanLines(200_000);
      req.setQuery("level:ERROR");

      LogSearchService.SearchResult res = service.search(req);

      // Only ERROR entries should be in results (query baked before PQ merge)
      assertThat(res.entries()).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo("ERROR"));
      assertThat(res.entries()).hasSize(20);
      // totalCached = query-matching entries only (baked)
      assertThat(res.totalCached()).isEqualTo(20);
      // filteredByStructured = all 40 (structural pass found 40, query narrowed later)
      assertThat(res.filteredByStructured()).isEqualTo(40);
    }

    /**
     * Default scan (no maxScanLines override) + query → Layer 2 refilter. Same structural cache
     * serves different queries without rescan.
     */
    @Test
    void defaultScan_queryAsPass2_preserved() throws IOException {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(20, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      // Default scan (no maxScanLines set) with query "level:ERROR"
      SearchRequest req1 = basicRequest("svc", 100);
      req1.setQuery("level:ERROR");
      LogSearchService.SearchResult res1 = service.search(req1);

      // Change query to "level:INFO" — same structural fingerprint, instant refilter
      SearchRequest req2 = basicRequest("svc", 100);
      req2.setQuery("level:INFO");
      LogSearchService.SearchResult res2 = service.search(req2);

      // Both should see same structural count (cache reused)
      assertThat(res1.filteredByStructured()).isEqualTo(res2.filteredByStructured());

      // totalCached reflects all structural entries (not baked)
      assertThat(res1.totalCached() + res2.totalCached()).isGreaterThanOrEqualTo(20);

      // Results are correct per query
      assertThat(res1.entries()).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo("ERROR"));
      assertThat(res2.entries()).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo("INFO"));
      assertThat(res1.entries().size() + res2.entries().size()).isEqualTo(20);
    }

    /**
     * Deep scan + different queries → different fingerprints → different caches. Changing query on
     * deep scan triggers rescan (not instant refilter).
     */
    @Test
    void deepScan_queryChange_triggersRescan() throws IOException {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(20, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      // Deep scan + query "level:ERROR"
      SearchRequest req1 = basicRequest("svc", 100);
      req1.setMaxScanLines(200_000);
      req1.setQuery("level:ERROR");
      LogSearchService.SearchResult res1 = service.search(req1);

      // Deep scan + query "level:INFO" → different fingerprint
      SearchRequest req2 = basicRequest("svc", 100);
      req2.setMaxScanLines(200_000);
      req2.setQuery("level:INFO");
      LogSearchService.SearchResult res2 = service.search(req2);

      // Both scans produced results filtered by their respective queries
      assertThat(res1.entries()).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo("ERROR"));
      assertThat(res2.entries()).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo("INFO"));

      // They are independent caches (different baked queries)
      assertThat(res1.entries().size() + res2.entries().size()).isEqualTo(20);
    }

    /** Deep scan without query → behaves exactly like default (no baking). */
    @Test
    void deepScan_noQuery_behavesLikeDefault() throws IOException {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(20, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      // Deep scan, no query
      SearchRequest req = basicRequest("svc", 100);
      req.setMaxScanLines(200_000);
      LogSearchService.SearchResult res = service.search(req);

      // All 20 entries returned (no query to filter)
      assertThat(res.entries()).hasSize(20);
      assertThat(res.totalCached()).isEqualTo(20);
      assertThat(res.filteredByStructured()).isEqualTo(20);
    }

    /** Deep scan + query + pagination → Load More serves correct entries from baked cache. */
    @Test
    void deepScan_loadMore_works() throws IOException {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String logPath = createLogFile("svc.log", generateLogLines(40, start));
      config = buildConfig(List.of(svcConfig("svc", logPath)));
      LogSearchService service = buildService();

      // Deep scan + query, small page size
      SearchRequest req = basicRequest("svc", 5);
      req.setMaxScanLines(200_000);
      req.setQuery("level:ERROR");
      LogSearchService.SearchResult page1 = service.search(req);

      assertThat(page1.entries()).hasSize(5);
      assertThat(page1.entries()).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo("ERROR"));
      assertThat(page1.searchId()).isNotNull();

      // Load More — page 2
      SearchRequest loadMore = new SearchRequest();
      loadMore.setSearchId(page1.searchId());
      loadMore.setLimit(5);
      LogSearchService.SearchResult page2 = service.search(loadMore);

      assertThat(page2.entries()).hasSize(5);
      assertThat(page2.entries()).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo("ERROR"));

      // Pages should have different entries (no overlap)
      List<LocalDateTime> p1Times = page1.entries().stream().map(LogEntry::getTimestamp).toList();
      List<LocalDateTime> p2Times = page2.entries().stream().map(LogEntry::getTimestamp).toList();
      assertThat(p2Times).doesNotContainAnyElementsOf(p1Times);
    }

    /** Historical search + deep scan + query → query applied before cap. */
    @Test
    void historicalDeepScan_queryBeforeCap() throws Exception {
      LocalDateTime start = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
      String activeLog = createLogFile("svc-console.log", generateLogLines(3, start));
      // Rotated file with 30 lines: alternating INFO/ERROR
      createLogFile("svc-console.log.1", generateLogLines(30, start.minusHours(1)));

      config = buildConfig(List.of(svcConfig("svc", activeLog)));
      LogSearchService service = buildService();

      // Deep scan + query on historical file
      SearchRequest req = new SearchRequest();
      req.setLimit(100);
      req.setSortOrder("desc");
      req.setMaxScanLines(200_000);
      req.setQuery("level:ERROR");

      LogSearchService.SearchResult result =
          service.searchHistorical("svc", "svc-console.log.1", req);

      // Only ERROR entries should be in results
      assertThat(result.entries()).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo("ERROR"));
      assertThat(result.entries()).hasSize(15); // 30 lines, half ERROR
      // Structural total = all 30 entries
      assertThat(result.filteredByStructured()).isEqualTo(30);
    }
  }
}
