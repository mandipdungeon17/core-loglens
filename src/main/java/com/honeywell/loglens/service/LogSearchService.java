package com.honeywell.loglens.service;

import com.honeywell.loglens.config.LogLensConfig;
import com.honeywell.loglens.model.LogEntry;
import com.honeywell.loglens.model.SearchRequest;
import com.honeywell.loglens.model.ServiceConfig;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import lombok.extern.slf4j.Slf4j;
import org.owasp.encoder.Encode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scans log files using the optimal strategy for each search scenario.
 *
 * <pre>
 * ┌─────────────────────────────────────────┬──────────────────────────┐
 * │ Scenario                                │ Strategy                 │
 * ├─────────────────────────────────────────┼──────────────────────────┤
 * │ .gz (live scan, raw stream)             │ FORWARD                  │
 * │ .gz (historical, decompressed to temp)  │ same as non-gz below     │
 * │ ASC + any time filter                   │ BINARY_THEN_FORWARD      │
 * │ ASC + structural filters (no time)      │ BACKWARD                 │
 * │ ASC + no filters                        │ FORWARD                  │
 * │ DESC + any time filter                  │ BINARY_THEN_BACKWARD     │
 * │ DESC + no time filter (default)         │ BACKWARD                 │
 * └─────────────────────────────────────────┴──────────────────────────┘
 * </pre>
 *
 * <p>Pagination (Load More): Every SearchResult carries a searchId (cache session key). The next
 * request sends that searchId back — the server serves the next page from its in-memory cache (zero
 * disk I/O). Each user/tab gets an isolated session with its own cursor. Falls back to offset-based
 * scanning if cache expired (nextOffsets provides per-service byte positions for resumption).
 */
@Slf4j
@Service
public class LogSearchService {

  private final LogLensConfig config;
  private final LogParserService parser;
  private final QueryEngine queryEngine;
  private final ExecutorService scanPool;

  // ── Multi-user search cache: two-layer (structural + per-session views) ─────
  private static final long CACHE_TTL_MS = 15 * 60_000; // 15 minutes (sliding)
  private static final int CACHE_MAX_ENTRIES = 100_000; // max entries per cache
  private static final int MAX_CACHES = 5; // max concurrent structural caches
  private static final int MAX_SESSIONS_PER_CACHE = 10; // max concurrent user sessions per cache

  private final ConcurrentHashMap<String, SearchCache> cacheStore = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, File> decompressedFiles = new ConcurrentHashMap<>();
  private final Object decompressLock =
      new Object(); // dedicated lock — avoid synchronizing on ConcurrentHashMap
  private final AtomicLong requestCount = new AtomicLong(0);

  /** Layer 1: Structural cache — shared across user sessions, keyed by filter fingerprint. */
  private static class SearchCache {
    final List<LogEntry> structuralEntries;
    final int structuralTotal; // actual count before cap
    final String sortOrder;
    final String strategy;
    final String fingerprint; // hash of structural filters
    final Map<String, Long> fileSizes; // logFile path → file size at scan time
    final String bakedQuery; // non-null = query pre-applied during scan (deep scan mode)
    final Instant createdAt;
    volatile Instant lastAccessed; // sliding TTL — reset on every access

    // Layer 2: Per-user sessions — each has its own query view + cursor
    final ConcurrentHashMap<String, CacheSession> sessions = new ConcurrentHashMap<>();
    final Object sessionsLock =
        new Object(); // dedicated lock — avoid synchronizing on ConcurrentHashMap

    SearchCache(
        List<LogEntry> entries,
        int structuralTotal,
        String sortOrder,
        String strategy,
        String fingerprint,
        Map<String, Long> fileSizes,
        String bakedQuery) {
      this.structuralEntries = entries;
      this.structuralTotal = structuralTotal;
      this.sortOrder = sortOrder;
      this.strategy = strategy;
      this.fingerprint = fingerprint;
      this.fileSizes = fileSizes;
      this.bakedQuery = bakedQuery;
      this.createdAt = Instant.now();
      this.lastAccessed = this.createdAt;
    }

    void touch() {
      this.lastAccessed = Instant.now();
    }

    boolean isExpired() {
      return Instant.now().toEpochMilli() - lastAccessed.toEpochMilli() > CACHE_TTL_MS;
    }

    void evictExpiredSessions() {
      sessions.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    void evictOldestSession() {
      String oldest = null;
      Instant oldestTime = null;
      for (Map.Entry<String, CacheSession> e : sessions.entrySet()) {
        if (oldestTime == null || e.getValue().lastAccessed.isBefore(oldestTime)) {
          oldestTime = e.getValue().lastAccessed;
          oldest = e.getKey();
        }
      }
      if (oldest != null) sessions.remove(oldest);
    }
  }

  /**
   * Layer 2: Per-user session — owns its own query view and cursor. Thread-safe via
   * synchronization.
   */
  private static class CacheSession {
    final String searchId;
    final String query; // null = no query filter
    final List<LogEntry> activeView; // query-filtered subset (or structural if no query)
    final Map<String, Long> cumulativeOffsets; // per-service offsets across all served pages
    volatile int cursor; // pagination position in activeView
    volatile Instant lastAccessed;

    CacheSession(String query, List<LogEntry> activeView) {
      this.searchId = UUID.randomUUID().toString();
      this.query = query;
      this.activeView = activeView;
      this.cumulativeOffsets = new ConcurrentHashMap<>();
      this.cursor = 0;
      this.lastAccessed = Instant.now();
    }

    void touch() {
      this.lastAccessed = Instant.now();
    }

    boolean isExpired() {
      return Instant.now().toEpochMilli() - lastAccessed.toEpochMilli() > CACHE_TTL_MS;
    }
  }

  /** Lookup result for finding a session by searchId across all caches. */
  private record SessionLookup(SearchCache cache, CacheSession session) {}

  public LogSearchService(LogLensConfig config, LogParserService parser, QueryEngine queryEngine) {
    this.config = config;
    this.parser = parser;
    this.queryEngine = queryEngine;

    int cores = Runtime.getRuntime().availableProcessors();
    int serviceCount = config.getServices() != null ? config.getServices().size() : 1;
    long heapMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);

    int poolSize;
    if (config.getScanPoolSize() > 0) {
      poolSize = config.getScanPoolSize();
    } else {
      int cpuBound = Math.max(2, (int) (cores * 0.6));
      int memBound = Math.max(2, (int) (heapMB / 150));
      poolSize = Math.max(1, Math.min(serviceCount, Math.min(cpuBound, memBound)));
    }

    log.info(
        "Scan thread pool: {} threads (cores={}, configuredServices={}, heapMB={})",
        poolSize,
        cores,
        serviceCount,
        heapMB);

    this.scanPool =
        Executors.newFixedThreadPool(
            poolSize,
            new ThreadFactory() {
              private final AtomicInteger counter = new AtomicInteger(0);

              public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "loglens-scan-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
              }
            });
  }

  @PreDestroy
  void shutdownPool() {
    scanPool.shutdownNow();
    cacheStore.clear();
    cleanupDecompressedFiles();
  }

  /**
   * Deletes all decompressed temp files. Only removes files created by decompressGz() (stored in
   * java.io.tmpdir with "loglens-" prefix) — never touches original .gz files.
   */
  private void cleanupDecompressedFiles() {
    for (Map.Entry<String, File> entry : decompressedFiles.entrySet()) {
      File tmp = entry.getValue();
      if (tmp.exists() && tmp.getName().startsWith("loglens-")) {
        if (tmp.delete()) {
          log.debug("Deleted temp file: {}", tmp.getAbsolutePath());
        }
      }
    }
    decompressedFiles.clear();
  }

  /**
   * Removes decompressed temp files that no longer have an active cache referencing them. A temp
   * file is "orphaned" if no surviving cache's fileSizes contains its .gz source path.
   */
  private void cleanupOrphanedTempFiles() {
    if (decompressedFiles.isEmpty()) return;
    // Collect all .gz paths still referenced by active caches
    Set<String> activePaths = new HashSet<>();
    for (SearchCache c : cacheStore.values()) {
      if (c.fileSizes != null) {
        activePaths.addAll(c.fileSizes.keySet());
      }
    }
    decompressedFiles
        .entrySet()
        .removeIf(
            entry -> {
              if (!activePaths.contains(entry.getKey())) {
                File tmp = entry.getValue();
                if (tmp.exists() && tmp.getName().startsWith("loglens-")) {
                  if (tmp.delete()) {
                    log.debug("Cleaned up orphaned temp file: {}", tmp.getAbsolutePath());
                  } else {
                    log.warn("Failed to delete orphaned temp file: {}", tmp.getAbsolutePath());
                  }
                }
                return true;
              }
              return false;
            });
  }

  /** Evict expired caches and sessions every 60 seconds to free memory. */
  @Scheduled(fixedRate = 60_000)
  void evictExpiredCache() {
    // Evict expired sessions from surviving caches first
    for (SearchCache c : cacheStore.values()) {
      c.evictExpiredSessions();
    }
    int before = cacheStore.size();
    cacheStore.entrySet().removeIf(e -> e.getValue().isExpired());
    int removed = before - cacheStore.size();
    if (removed > 0) {
      // Clean up temp files if no historical caches remain referencing them
      cleanupOrphanedTempFiles();
      log.info("Cache eviction: removed {} expired (remaining: {})", removed, cacheStore.size());
    }
  }

  /** Clears all caches — called via admin API. */
  public void clearAllCaches() {
    int size = cacheStore.size();
    cacheStore.clear();
    cleanupDecompressedFiles();
    log.info("All caches cleared ({} removed)", size);
  }

  /** Returns cache/session/request metrics for the metrics endpoint. */
  public Map<String, Object> cacheMetrics() {
    int caches = cacheStore.size();
    int sessions = 0;
    for (SearchCache c : cacheStore.values()) {
      sessions += c.sessions.size();
    }
    return Map.of(
        "cacheSize", caches,
        "sessionCount", sessions,
        "reqCount", requestCount.get());
  }

  /** Data returned by {@link #getExportEntries} for streaming download. */
  public record ExportData(List<LogEntry> entries, String sortOrder) {}

  /**
   * Returns the full filtered+sorted entry list for a cache session (for export/download). Returns
   * null if the session is expired or not found.
   */
  public ExportData getExportEntries(String searchId) {
    SessionLookup lookup = findSessionBySearchId(searchId);
    if (lookup == null || lookup.cache().isExpired() || lookup.session().isExpired()) {
      return null;
    }
    lookup.session().touch();
    lookup.cache().touch();
    return new ExportData(lookup.session().activeView, lookup.cache().sortOrder);
  }

  /** Metadata for a single file in a service's log directory. */
  public record FileInfo(String name, long sizeKb, String lastModified) {}

  /** Finds a service config by name, or null if not found. */
  private ServiceConfig findService(String name) {
    return config.getServices().stream()
        .filter(s -> s.getName().equals(name))
        .findFirst()
        .orElse(null);
  }

  /**
   * Checks if a filename is a valid rotated log for the given base name. Accepts two rotation
   * patterns: - Traditional: baseName + "." + suffix (e.g. "app.log.1", "app.log.2.gz") -
   * Date-rotated: baseName + "-" + suffix (e.g. "app-04-20-2026-1.log.gz") Rejects unrelated files
   * that merely share a prefix (e.g. "application.yml").
   */
  private boolean isRotatedLogFile(String fileName, String baseName) {
    if (!fileName.startsWith(baseName)) return false;
    if (fileName.length() <= baseName.length()) return false;
    char separator = fileName.charAt(baseName.length());
    return separator == '.' || separator == '-';
  }

  /** Extracts base name from a log file path by stripping the .log extension. */
  private String extractBaseName(String logFilePath) {
    String activeFileName = new File(logFilePath).getName();
    return activeFileName.endsWith(".log")
        ? activeFileName.substring(0, activeFileName.length() - 4)
        : activeFileName;
  }

  /**
   * Decompresses a .gz file to a temp file for random-access scanning. Caches the decompressed file
   * per .gz path so repeated searches reuse it. Thread-safe: concurrent requests for the same .gz
   * wait for the first to finish. Safety: only deletes temp files created by this method, never
   * original .gz files.
   */
  private File decompressGz(File gzFile) throws IOException {
    String key = gzFile.getCanonicalPath();
    File existing = decompressedFiles.get(key);
    if (existing != null && existing.exists()) {
      return existing;
    }
    // Global lock prevents duplicate decompression; acceptable since .gz decompression is
    // infrequent
    synchronized (decompressLock) {
      // Double-check after acquiring lock
      existing = decompressedFiles.get(key);
      if (existing != null && existing.exists()) {
        return existing;
      }
      File tmp = File.createTempFile("loglens-", ".log");
      tmp.deleteOnExit();
      log.info(
          "Decompressing {} → {} ({} KB compressed)",
          sanitizeForLog(gzFile.getName()),
          tmp.getAbsolutePath(),
          (gzFile.length() + 1023) / 1024);
      try (InputStream in =
              new GZIPInputStream(new BufferedInputStream(new FileInputStream(gzFile), 65536));
          OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp), 65536)) {
        long bytes = in.transferTo(out);
        if (bytes == 0) {
          log.warn("Decompressed 0 bytes from {}", sanitizeForLog(gzFile.getName()));
        }
      }
      log.info(
          "Decompressed {} → {} KB",
          sanitizeForLog(gzFile.getName()),
          (tmp.length() + 1023) / 1024);
      decompressedFiles.put(key, tmp);
      return tmp;
    }
  }

  /**
   * Lists all log files in the service's log directory, excluding the active log file. Returns
   * empty list if service not found or directory doesn't exist.
   */
  public List<FileInfo> listServiceFiles(String serviceName) {
    ServiceConfig svc = findService(serviceName);
    if (svc == null) return Collections.emptyList();
    File dir = new File(svc.getLogFile()).getParentFile();
    if (dir == null || !dir.isDirectory()) return Collections.emptyList();
    String activeFileName = new File(svc.getLogFile()).getName();
    String baseName = extractBaseName(svc.getLogFile());
    File[] files = dir.listFiles();
    if (files == null) return Collections.emptyList();
    return Arrays.stream(files)
        .filter(
            f ->
                f.isFile()
                    && !f.getName().equals(activeFileName)
                    && isRotatedLogFile(f.getName(), baseName))
        .sorted(Comparator.comparingLong(File::lastModified).reversed())
        .map(
            f ->
                new FileInfo(
                    f.getName(),
                    (f.length() + 1023) / 1024,
                    Instant.ofEpochMilli(f.lastModified()).toString()))
        .collect(Collectors.toList());
  }

  /**
   * Searches a single historical/archived log file for a specific service. Reuses existing scan
   * strategies, parsers, filters, and cache layer. The file must be in the service's configured log
   * directory.
   */
  public SearchResult searchHistorical(String serviceName, String fileName, SearchRequest req)
      throws IOException {
    ServiceConfig svc = findService(serviceName);
    if (svc == null)
      throw new IllegalArgumentException("Unknown service: " + sanitizeForLog(serviceName));

    if (fileName.contains("/") || fileName.contains("\\") || fileName.contains(".."))
      throw new IllegalArgumentException("Invalid file name");

    File dir = new File(svc.getLogFile()).getParentFile();
    if (dir == null || !dir.isDirectory())
      throw new IllegalArgumentException(
          "Log directory not found for service: " + sanitizeForLog(serviceName));

    // Restrict to rotated log files (must share the active log file's base name prefix, but not the
    // active file itself)
    String activeFileName = new File(svc.getLogFile()).getName();
    String baseName = extractBaseName(svc.getLogFile());
    if (fileName.equals(activeFileName))
      throw new IllegalArgumentException("Use live search for the active log file");
    if (!isRotatedLogFile(fileName, baseName))
      throw new IllegalArgumentException("File is not a rotated log for this service");

    File file = new File(dir, fileName);
    if (!file.exists() || !file.isFile())
      throw new IllegalArgumentException("File not found: " + sanitizeForLog(fileName));

    String canonicalPath = file.getCanonicalFile().getAbsolutePath();
    if (!file.getCanonicalFile().toPath().startsWith(dir.getCanonicalFile().toPath()))
      throw new IllegalArgumentException("Path traversal attempt");

    requestCount.incrementAndGet();
    int limit = req.getLimit() > 0 ? req.getLimit() : Integer.MAX_VALUE;
    int safeCap = 50_000;
    limit = Math.min(limit, safeCap);

    // Load More — serve from existing session (validate it belongs to this file)
    if (nb(req.getSearchId())) {
      SessionLookup lookup = findSessionBySearchId(req.getSearchId());
      if (lookup != null && !lookup.cache().isExpired() && !lookup.session().isExpired()) {
        if (lookup.cache().fileSizes != null
            && lookup.cache().fileSizes.containsKey(canonicalPath)) {
          return serveCachePage(lookup.cache(), lookup.session(), limit);
        }
        log.info(
            "Historical cache mismatch for searchId={} requestedFile={}",
            sanitizeForLog(req.getSearchId()),
            sanitizeForLog(canonicalPath));
      } else {
        log.info("Cache miss for historical searchId={}", sanitizeForLog(req.getSearchId()));
      }
    }

    // Cache reuse check (fingerprint includes canonical file path)
    String fingerprint = computeHistoricalFingerprint(canonicalPath, req);
    boolean isAsc = "asc".equalsIgnoreCase(req.getSortOrder());
    SearchCache existing = cacheStore.get(fingerprint);
    if (existing != null && !existing.isExpired()) {
      if (isAsc || isCacheFresh(existing)) {
        log.info("Historical cache reuse: {} entries", existing.structuralEntries.size());
        existing.touch();
        CacheSession session = createSession(existing, req.getQuery());
        return serveCachePage(existing, session, limit);
      }
      cacheStore.remove(fingerprint);
    }

    // Single-file scan — decompress .gz only when random-access strategy is needed
    boolean desc = !isAsc;
    ResolvedFilters filters = resolveFilters(req);

    // On cache-miss fallback, translate serviceOffsets to fileOffset for resume
    SearchRequest scanReq = req;
    if (req.getServiceOffsets() != null && req.getServiceOffsets().containsKey(serviceName)) {
      scanReq = cloneWithOffset(req, req.getServiceOffsets().get(serviceName));
    }

    // For .gz: decompress to temp file when random-access strategy is needed.
    // ASC + no filters can stream the raw .gz directly (FORWARD scan).
    File scanFile = file;
    if (file.getName().endsWith(".gz")) {
      boolean needsRandomAccess =
          isAsc
              ? (scanReq.getFrom() != null
                  || scanReq.getTo() != null
                  || hasStructuralFilters(scanReq))
              : true; // DESC always benefits from BACKWARD
      if (needsRandomAccess) {
        scanFile = decompressGz(file);
      }
    }

    Strategy strat = selectStrategy(scanFile, scanReq);
    log.info(
        "[{}] historical scan: file={}, strategy={}",
        svc.getName(),
        sanitizeForLog(fileName),
        strat);

    List<LogEntry> results;
    try {
      results =
          switch (strat) {
            case BACKWARD -> scanBackward(scanFile, svc.getName(), scanReq, filters);
            case FORWARD -> scanForward(scanFile, svc.getName(), scanReq, filters);
            case BINARY_THEN_BACKWARD ->
                scanBinaryThenBackward(scanFile, svc.getName(), scanReq, filters);
            case BINARY_THEN_FORWARD ->
                scanBinaryThenForward(scanFile, svc.getName(), scanReq, filters);
          };
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      log.error(
          "Error scanning historical file [{}] for service [{}] using strategy [{}]",
          sanitizeForLog(fileName),
          svc.getName(),
          strat,
          e);
      throw new IOException(
          "Scan failed for " + sanitizeForLog(fileName) + ": " + e.getMessage(), e);
    }

    // Sort + cap
    int structuralTotal = results.size();

    // Deep scan hybrid: filter by query BEFORE sorting+capping to maximize recall
    boolean deep = isDeepScan(req);
    String q = (req.getQuery() != null && !req.getQuery().isBlank()) ? req.getQuery() : null;
    boolean preFilter = deep && q != null;
    if (preFilter) {
      final String query = q;
      results =
          results.stream()
              .filter(e -> queryEngine.matches(e, query))
              .collect(Collectors.toCollection(ArrayList::new));
    }

    Comparator<LocalDateTime> tsComp =
        desc
            ? Comparator.nullsLast(Comparator.reverseOrder())
            : Comparator.nullsLast(Comparator.naturalOrder());
    results.sort(Comparator.comparing(LogEntry::getTimestamp, tsComp));
    if (results.size() > CACHE_MAX_ENTRIES) {
      results = new ArrayList<>(results.subList(0, CACHE_MAX_ENTRIES));
    }

    // Cache + serve
    String bakedQuery = preFilter ? q : null;
    Map<String, Long> fileSizes = Map.of(canonicalPath, file.length());
    SearchCache newCache =
        new SearchCache(
            results,
            structuralTotal,
            desc ? "desc" : "asc",
            strat.name(),
            fingerprint,
            fileSizes,
            bakedQuery);
    log.info(
        "Historical cache: {} entries (of {} structural), file={}, bakedQuery={}",
        results.size(),
        structuralTotal,
        sanitizeForLog(fileName),
        bakedQuery != null);
    CacheSession session = createSession(newCache, req.getQuery());
    if (cacheStore.size() >= MAX_CACHES && !cacheStore.containsKey(fingerprint)) {
      evictLru();
    }
    cacheStore.put(fingerprint, newCache);
    return serveCachePage(newCache, session, limit);
  }

  /** Fingerprint for historical searches — includes file path instead of service names. */
  private String computeHistoricalFingerprint(String filePath, SearchRequest req) {
    boolean deep = isDeepScan(req);
    String q = (req.getQuery() != null && !req.getQuery().isBlank()) ? req.getQuery() : null;
    if (deep && q != null) {
      int h =
          Objects.hash(
              "HISTORICAL",
              filePath,
              req.getSortOrder(),
              req.getLevel(),
              mergeList(req.getTraceIds(), req.getTraceId()),
              mergeList(req.getSpanIds(), req.getSpanId()),
              req.getUserId(),
              req.getSiteId(),
              req.getTenantId(),
              req.getLogger(),
              req.getMessage(),
              req.getFrom(),
              req.getTo(),
              resolveMaxScan(req),
              q);
      return "hdq-" + Integer.toHexString(h);
    }
    int h =
        Objects.hash(
            "HISTORICAL",
            filePath,
            req.getSortOrder(),
            req.getLevel(),
            mergeList(req.getTraceIds(), req.getTraceId()),
            mergeList(req.getSpanIds(), req.getSpanId()),
            req.getUserId(),
            req.getSiteId(),
            req.getTenantId(),
            req.getLogger(),
            req.getMessage(),
            req.getFrom(),
            req.getTo(),
            resolveMaxScan(req));
    return "h-" + Integer.toHexString(h);
  }

  /** Chunk size for backward reading — 64KB per block (avoids per-byte seek overhead) */
  private static final int CHUNK = 65536;

  // ────────────────────────────────────────────────────────────────────────────
  // Scan strategies — selected per file per request
  // ────────────────────────────────────────────────────────────────────────────

  private enum Strategy {
    BACKWARD,
    FORWARD,
    BINARY_THEN_BACKWARD,
    BINARY_THEN_FORWARD
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Precomputed filter values — resolved once per search, reused for every entry
  // ────────────────────────────────────────────────────────────────────────────

  private record ResolvedFilters(
      String level,
      List<String> traceIds,
      List<String> spanIds,
      String userId,
      String siteId,
      String tenantId,
      String logger,
      String message,
      LocalDateTime from,
      LocalDateTime to) {}

  private ResolvedFilters resolveFilters(SearchRequest req) {
    return new ResolvedFilters(
        req.getLevel(),
        mergeList(req.getTraceIds(), req.getTraceId()),
        mergeList(req.getSpanIds(), req.getSpanId()),
        req.getUserId(),
        req.getSiteId(),
        req.getTenantId(),
        req.getLogger(),
        req.getMessage(),
        req.getFrom(),
        req.getTo());
  }

  /**
   * Chooses the best scan strategy for this file + request combination.
   *
   * <p>Logic: 1. .gz → always FORWARD (cannot seek inside a gzip stream) 2. ASC + any time filter →
   * BINARY_THEN_FORWARD (binary seeks to fromTime or start-of-file if only toTime; avoids wasting
   * maxScanLines on entries outside the target window on large files) 3. ASC + no time filter +
   * structural filters → BACKWARD (data likely recent, sort ASC applied after merge) 4. ASC + no
   * time filter + no structural filters → FORWARD (oldest-first) 5. DESC + "to" filter set →
   * BINARY_THEN_BACKWARD (binary seeks to toTime, then scans backward into the from-to window.
   * Critical for large files where BACKWARD from end-of-file would exhaust maxScanLines before
   * reaching the target window.) 6. DESC + "from" set (no "to") → BINARY_THEN_BACKWARD (uses
   * end-of-file as implicit to, binary seek is a no-op but early exit on from still works. Safer
   * than BACKWARD for large files.) 7. Everything else → BACKWARD
   */
  private Strategy selectStrategy(File file, SearchRequest req) {
    if (file.getName().endsWith(".gz")) return Strategy.FORWARD;

    boolean isAsc = "asc".equalsIgnoreCase(req.getSortOrder());
    boolean hasTimeFilter = req.getFrom() != null || req.getTo() != null;

    if (isAsc) {
      if (hasTimeFilter) return Strategy.BINARY_THEN_FORWARD;
      // ASC + no time filter + structural filters: data is likely recent (near end),
      // so scan backward (the post-merge sort will order ASC).
      // Without this, FORWARD scans from byte 0 and exhausts maxScanLines before
      // reaching recent entries — returning 0 results for traceId/level/etc. filters.
      if (hasStructuralFilters(req)) return Strategy.BACKWARD;
      return Strategy.FORWARD;
    }

    // DESC with any time filter → binary seek avoids wasting maxScanLines on
    // entries between end-of-file and the target window. On 20+ GB files, BACKWARD
    // from EOF may only cover the last 2-3 minutes of logs within 100K raw lines.
    if (hasTimeFilter) return Strategy.BINARY_THEN_BACKWARD;

    return Strategy.BACKWARD;
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Public search API
  // ────────────────────────────────────────────────────────────────────────────

  public record SearchResult(
      List<LogEntry> entries,
      int totalMatched,
      int filteredByStructured,
      int limit,
      String sortOrder,
      Map<String, Long> nextOffsets,
      String strategy,
      String searchId,
      int totalCached) {
    public boolean isTruncated() {
      // searchId present = more pages in cache; nextOffsets without searchId = disk-scan truncation
      return (searchId != null && totalCached > 0 && entries.size() < totalCached)
          || (searchId == null && nextOffsets != null && !nextOffsets.isEmpty());
    }
  }

  public SearchResult search(SearchRequest req) {
    requestCount.incrementAndGet();
    int limit = req.getLimit() > 0 ? req.getLimit() : Integer.MAX_VALUE;
    int safeCap = 50_000;
    limit = Math.min(limit, safeCap);

    // ── 1. LOAD MORE — serve next page from existing session ─────
    if (nb(req.getSearchId())) {
      SessionLookup lookup = findSessionBySearchId(req.getSearchId());
      if (lookup != null && !lookup.cache().isExpired() && !lookup.session().isExpired()) {
        return serveCachePage(lookup.cache(), lookup.session(), limit);
      }
      log.info(
          "Cache miss for searchId={} — falling back to disk scan",
          sanitizeForLog(req.getSearchId()));
    }

    // ── 2. CHECK if existing cache is reusable ───────────────────────
    List<ServiceConfig> targets = resolveServices(req.getServices());
    String fingerprint = computeFingerprint(req, targets);
    boolean isAsc = "asc".equalsIgnoreCase(req.getSortOrder());
    SearchCache existing = cacheStore.get(fingerprint);

    if (existing != null && !existing.isExpired()) {
      // Structural filters match. Check data freshness:
      // ASC → old data is immutable → always fresh
      // DESC → check if any log file grew since last scan
      if (isAsc || isCacheFresh(existing)) {
        log.info(
            "Cache reuse ({}): {} structural entries, applying query",
            isAsc ? "ASC-immutable" : "files-unchanged",
            existing.structuralEntries.size());
        existing.touch();
        CacheSession session = createSession(existing, req.getQuery());
        return serveCachePage(existing, session, limit);
      }
      log.info("Cache stale — file sizes changed, re-scanning");
      cacheStore.remove(fingerprint);
    }

    // ── 3. FRESH SCAN — disk I/O ────────────────────────────────────
    boolean desc = !isAsc;
    ResolvedFilters filters = resolveFilters(req);

    // Record file sizes BEFORE scan for freshness check on next search
    Map<String, Long> fileSizes = new LinkedHashMap<>();
    for (ServiceConfig svc : targets) {
      File f = new File(svc.getLogFile());
      if (f.exists()) fileSizes.put(svc.getLogFile(), f.length());
    }

    // Scan all services in PARALLEL
    Map<String, Long> svcOffsets =
        req.getServiceOffsets() != null ? req.getServiceOffsets() : Collections.emptyMap();

    List<CompletableFuture<List<LogEntry>>> futures =
        targets.stream()
            .map(
                svc ->
                    CompletableFuture.supplyAsync(
                        () -> {
                          try {
                            File file = new File(svc.getLogFile());
                            SearchRequest svcReq = req;
                            Long perSvcOffset = svcOffsets.get(svc.getName());
                            if (perSvcOffset != null) {
                              svcReq = cloneWithOffset(req, perSvcOffset);
                            } else if (req.getFileOffset() != null) {
                              svcReq = cloneWithOffset(req, req.getFileOffset());
                            }
                            Strategy strat = selectStrategy(file, svcReq);
                            log.info("[{}] strategy={}", svc.getName(), strat);
                            return switch (strat) {
                              case BACKWARD -> scanBackward(file, svc.getName(), svcReq, filters);
                              case FORWARD -> scanForward(file, svc.getName(), svcReq, filters);
                              case BINARY_THEN_BACKWARD ->
                                  scanBinaryThenBackward(file, svc.getName(), svcReq, filters);
                              case BINARY_THEN_FORWARD ->
                                  scanBinaryThenForward(file, svc.getName(), svcReq, filters);
                            };
                          } catch (Exception e) {
                            log.warn("Error reading [{}]: {}", svc.getName(), e.getMessage());
                            return Collections.<LogEntry>emptyList();
                          }
                        },
                        scanPool))
            .collect(Collectors.toList());

    // ── Bounded merge: keep top CACHE_MAX_ENTRIES by timestamp ─────
    // PQ eviction comparator: "worst for desired sort" sits at peek().
    // DESC: oldest/null at peek (evict oldest, keep most recent)
    // ASC:  newest at peek (evict newest, keep oldest)
    // Null timestamps = unparseable first lines (not continuation lines — those are
    // buffered into the parent LogEntry by the parser). nullsLast keeps them safe
    // from eviction so they aren't silently dropped.
    Comparator<LogEntry> evictionComp =
        desc
            ? Comparator.comparing(
                LogEntry::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
            : Comparator.comparing(
                LogEntry::getTimestamp, Comparator.nullsLast(Comparator.reverseOrder()));

    PriorityQueue<LogEntry> heap =
        new PriorityQueue<>(Math.min(CACHE_MAX_ENTRIES + 1, 16384), evictionComp);

    // Deep scan hybrid: apply query before PQ merge for maximum recall
    boolean deep = isDeepScan(req);
    String q = (req.getQuery() != null && !req.getQuery().isBlank()) ? req.getQuery() : null;
    boolean preFilter = deep && q != null;

    int structuralTotal = 0;

    for (int i = 0; i < futures.size(); i++) {
      List<LogEntry> batch;
      try {
        batch = futures.get(i).get();
      } catch (Exception e) {
        log.warn("Future failed: {}", e.getMessage());
        futures.set(i, null);
        continue;
      }

      if (batch == null) {
        futures.set(i, null);
        continue;
      }

      structuralTotal += batch.size();

      for (LogEntry entry : batch) {
        if (preFilter && !queryEngine.matches(entry, q)) continue;
        if (heap.size() < CACHE_MAX_ENTRIES) {
          heap.add(entry);
        } else {
          LogEntry peek = heap.peek();
          if (peek != null && evictionComp.compare(entry, peek) > 0) {
            heap.poll();
            heap.add(entry);
          }
        }
      }

      futures.set(i, null); // release for GC
    }

    String usedStrategy =
        targets.isEmpty()
            ? "NONE"
            : selectStrategy(new File(targets.get(0).getLogFile()), req).name();

    // ── 4. CACHE results (structural, or structural+query if deep scan) ──
    // Drain heap into sorted list. PQ iterator is unordered; must sort.
    Comparator<LocalDateTime> tsComp =
        desc
            ? Comparator.nullsLast(Comparator.reverseOrder())
            : Comparator.nullsLast(Comparator.naturalOrder());
    List<LogEntry> toCache = new ArrayList<>(heap);
    toCache.sort(Comparator.comparing(LogEntry::getTimestamp, tsComp));

    String bakedQuery = preFilter ? q : null;
    SearchCache newCache =
        new SearchCache(
            toCache,
            structuralTotal,
            desc ? "desc" : "asc",
            usedStrategy,
            fingerprint,
            fileSizes,
            bakedQuery);
    log.info(
        "New cache: {} entries (of {} structural), fingerprint={}, bakedQuery={}",
        toCache.size(),
        structuralTotal,
        fingerprint,
        bakedQuery != null);

    // Apply query on cached structural results (Pass 2 from cache — skipped if baked)
    CacheSession session = createSession(newCache, req.getQuery());

    // LRU eviction: if at capacity, remove the oldest-accessed cache
    if (cacheStore.size() >= MAX_CACHES && !cacheStore.containsKey(fingerprint)) {
      evictLru();
    }
    cacheStore.put(fingerprint, newCache);
    return serveCachePage(newCache, session, limit);
  }

  // ── Cache helper methods ─────────────────────────────────────────────

  /** Checks if all log files have the same size as when cache was created. */
  private boolean isCacheFresh(SearchCache c) {
    for (Map.Entry<String, Long> entry : c.fileSizes.entrySet()) {
      File f = new File(entry.getKey());
      if (!f.exists() || f.length() != entry.getValue()) return false;
    }
    return true;
  }

  /** Finds a session by its searchId across all caches (for Load More). */
  private SessionLookup findSessionBySearchId(String searchId) {
    for (SearchCache c : cacheStore.values()) {
      CacheSession s = c.sessions.get(searchId);
      if (s != null) return new SessionLookup(c, s);
    }
    return null;
  }

  /** Evicts the least-recently-accessed cache to make room for a new one. */
  private void evictLru() {
    String oldestKey = null;
    Instant oldestTime = null;
    for (Map.Entry<String, SearchCache> entry : cacheStore.entrySet()) {
      if (oldestTime == null || entry.getValue().lastAccessed.isBefore(oldestTime)) {
        oldestTime = entry.getValue().lastAccessed;
        oldestKey = entry.getKey();
      }
    }
    if (oldestKey != null) {
      cacheStore.remove(oldestKey);
      cleanupOrphanedTempFiles();
      log.info("LRU eviction: removed cache (fingerprint={})", oldestKey);
    }
  }

  /** Creates a new per-user session from cached structural results. Thread-safe. */
  private CacheSession createSession(SearchCache cache, String query) {
    String q = (query != null && !query.isBlank()) ? query : null;
    List<LogEntry> view;
    if (q != null && cache.bakedQuery == null) {
      // Default mode: query not baked → apply as Pass 2 (instant Layer 2 refilter)
      view =
          cache.structuralEntries.stream()
              .filter(e -> queryEngine.matches(e, q))
              .collect(Collectors.toList());
    } else {
      // No query, OR query already baked into cache entries (deep scan mode)
      view = cache.structuralEntries;
    }
    CacheSession session = new CacheSession(q, view);
    // Synchronized to prevent exceeding MAX_SESSIONS under concurrent creation
    synchronized (cache.sessionsLock) {
      if (cache.sessions.size() >= MAX_SESSIONS_PER_CACHE) {
        cache.evictOldestSession();
      }
      cache.sessions.put(session.searchId, session);
    }
    cache.touch();
    return session;
  }

  /** Serves next page from a user session. Thread-safe per session. */
  private SearchResult serveCachePage(SearchCache cache, CacheSession session, int limit) {
    synchronized (session) {
      session.touch();
      cache.touch();
      int from = session.cursor;
      int to = Math.min(from + limit, session.activeView.size());
      List<LogEntry> page =
          (from < to)
              ? new ArrayList<>(session.activeView.subList(from, to))
              : Collections.emptyList();
      session.cursor = to;

      // Accumulate per-service offsets across all served pages (for fallback if cache expires)
      boolean isDesc = "desc".equalsIgnoreCase(cache.sortOrder);
      for (LogEntry e : page) {
        if (e.getService() != null && e.getFileOffset() != null) {
          session.cumulativeOffsets.merge(
              e.getService(), e.getFileOffset(), isDesc ? Math::min : Math::max);
        }
      }
      Map<String, Long> offsets = new LinkedHashMap<>(session.cumulativeOffsets);

      log.info(
          "Cache page: entries {}-{} of {} (query={}, structural={}, sessions={})",
          from,
          to,
          session.activeView.size(),
          session.query != null ? "yes" : "no",
          cache.structuralTotal,
          cache.sessions.size());

      return new SearchResult(
          page,
          session.activeView.size(),
          cache.structuralTotal,
          limit,
          cache.sortOrder,
          offsets,
          cache.strategy,
          session.searchId,
          session.activeView.size());
    }
  }

  /**
   * Computes fingerprint of structural filters. For deep scans with query, includes query (dq-
   * prefix).
   */
  private String computeFingerprint(SearchRequest req, List<ServiceConfig> targets) {
    List<String> svcNames =
        targets.stream().map(ServiceConfig::getName).sorted().collect(Collectors.toList());
    boolean deep = isDeepScan(req);
    String q = (req.getQuery() != null && !req.getQuery().isBlank()) ? req.getQuery() : null;
    // Deep scan + query: include query in fingerprint (changing query = cache miss = rescan)
    if (deep && q != null) {
      int h =
          Objects.hash(
              svcNames,
              req.getSortOrder(),
              req.getLevel(),
              mergeList(req.getTraceIds(), req.getTraceId()),
              mergeList(req.getSpanIds(), req.getSpanId()),
              req.getUserId(),
              req.getSiteId(),
              req.getTenantId(),
              req.getLogger(),
              req.getMessage(),
              req.getFrom(),
              req.getTo(),
              resolveMaxScan(req),
              q);
      return "dq-" + Integer.toHexString(h);
    }
    // Default scan or deep scan without query: fingerprint excludes query (Layer 2 works)
    int h =
        Objects.hash(
            svcNames,
            req.getSortOrder(),
            req.getLevel(),
            mergeList(req.getTraceIds(), req.getTraceId()),
            mergeList(req.getSpanIds(), req.getSpanId()),
            req.getUserId(),
            req.getSiteId(),
            req.getTenantId(),
            req.getLogger(),
            req.getMessage(),
            req.getFrom(),
            req.getTo(),
            resolveMaxScan(req));
    return Integer.toHexString(h);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Strategy 1 — BACKWARD
  // Read from end of file (or fileOffset) toward start in 64KB chunks.
  // Best for: presets, recent ranges, DESC no filter.
  // ────────────────────────────────────────────────────────────────────────────

  private List<LogEntry> scanBackward(
      File file, String svcName, SearchRequest req, ResolvedFilters filters) throws IOException {
    if (!file.exists()) return Collections.emptyList();

    List<LogEntry> results = new ArrayList<>();
    int maxScan = resolveMaxScan(req);
    int scanned = 0;

    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      long fileSize = raf.length();
      if (fileSize == 0) return results;

      // Resume from cursor if this is a "load more" request; else start from end
      long pointer =
          (req.getFileOffset() != null) ? Math.min(req.getFileOffset(), fileSize) : fileSize;
      log.info(
          "[{}] scanBackward: pointer={}, fileSize={}, from={}, to={}",
          svcName,
          pointer,
          fileSize,
          filters.from(),
          filters.to());
      ArrayDeque<String> entryBuf = new ArrayDeque<>();
      String leftover = "";

      while (pointer > 0 && scanned < maxScan) {
        long chunkStart = Math.max(0, pointer - CHUNK);
        int chunkLen = (int) (pointer - chunkStart);
        byte[] bytes = new byte[chunkLen];
        raf.seek(chunkStart);
        raf.readFully(bytes);

        // Avoid splitting multi-byte UTF-8 chars at chunk boundary.
        // If chunkStart > 0, back up past any UTF-8 continuation bytes (10xxxxxx).
        int trim = 0;
        if (chunkStart > 0) {
          while (trim < chunkLen && trim < 4 && (bytes[trim] & 0xC0) == 0x80) trim++;
        }
        pointer = chunkStart + trim;

        // Prepend leftover from previous chunk (it was the partial first line of that
        // chunk)
        String chunk = new String(bytes, trim, chunkLen - trim, StandardCharsets.UTF_8) + leftover;

        String[] lines = chunk.split("\n", -1);
        leftover = lines[0]; // partial line — carried to next iteration

        // Process from last line to first (right to left = newest to oldest within
        // chunk)
        for (int i = lines.length - 1; i >= 1; i--) {
          String line = stripCR(lines[i]);

          if (parser.isNewEntry(line)) {
            scanned++;
            entryBuf.addFirst(line);
            String block = String.join("\n", entryBuf);
            entryBuf.clear();

            LogEntry e = parser.parse(block, svcName, scanned);
            if (e != null) {
              // Early exit — gone past the fromTime window
              if (filters.from() != null
                  && e.getTimestamp() != null
                  && e.getTimestamp().isBefore(filters.from())) {
                return results;
              }
              if (matchesStructured(e, filters)) {
                // Set resume cursor: pointer is current backward scanning position.
                // Entries found later (further back) have lower offsets.
                // The last entry on a paginated page carries the right resume point.
                e.setFileOffset(pointer);
                results.add(e);
              }
            }
          } else {
            if (!line.isBlank()) entryBuf.addFirst(line);
          }
        }
      }

      // Flush the very first entry in the file (no leading \n before it)
      if (!leftover.isBlank() && scanned < maxScan) {
        entryBuf.addFirst(leftover);
        String block = String.join("\n", entryBuf);
        LogEntry e = parser.parse(block, svcName, scanned);
        if (e != null) {
          if (matchesStructured(e, filters)) {
            e.setFileOffset(pointer);
            results.add(e);
          }
        }
      }
    }
    return results;
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Strategy 2 — FORWARD
  // Read from start of file (or fileOffset) toward end.
  // Best for: ASC sort (with or without time filters), .gz files.
  //
  // Uses BufferedReader + manual byte tracking for non-gz files (RAF.readLine()
  // is byte-by-byte = extremely slow on large files).
  // For .gz files, uses BufferedReader via GZIPInputStream.
  // ────────────────────────────────────────────────────────────────────────────

  private List<LogEntry> scanForward(
      File file, String svcName, SearchRequest req, ResolvedFilters filters) throws IOException {
    if (!file.exists()) return Collections.emptyList();

    if (file.getName().endsWith(".gz")) {
      return scanForwardGz(file, svcName, req, filters);
    }

    List<LogEntry> results = new ArrayList<>();
    int scanned = 0;
    int maxScan = resolveMaxScan(req);

    long startOffset =
        (req.getFileOffset() != null && req.getFileOffset() > 0) ? req.getFileOffset() : 0;

    // Use BufferedReader for speed (RAF.readLine() is byte-by-byte = very slow).
    // Track byte position manually for pagination.
    try (FileInputStream fis = new FileInputStream(file)) {
      long toSkip = startOffset;
      while (toSkip > 0) {
        long s = fis.skip(toSkip);
        if (s <= 0) break;
        toSkip -= s;
      }
      long bytePos = startOffset;

      BufferedReader reader =
          new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8));

      // Line ending size in bytes — assumes \n (Unix). WES logs are from Linux servers.
      int newlineBytes = 1;

      // If resuming from offset, skip the partial line we may have landed in
      if (startOffset > 0) {
        String skipped = reader.readLine();
        if (skipped != null)
          bytePos += skipped.getBytes(StandardCharsets.UTF_8).length + newlineBytes;
      }

      StringBuilder buf = new StringBuilder();
      long entryStartByte = bytePos;
      int entryStartLine = 0;
      int rawLines = 0;

      String line;
      while ((line = reader.readLine()) != null && scanned < maxScan) {
        // Use actual byte length for accurate offset tracking (handles UTF-8 + \r\n)
        int lineBytes = line.getBytes(StandardCharsets.UTF_8).length + newlineBytes;
        rawLines++;
        if (parser.isNewEntry(line)) {
          scanned++;
          if (buf.length() > 0) {
            LogEntry e = parser.parse(buf.toString(), svcName, entryStartLine);
            if (e != null) {
              e.setFileOffset(entryStartByte);
              if (filters.to() != null
                  && e.getTimestamp() != null
                  && e.getTimestamp().isAfter(filters.to())) {
                return results;
              }
              if (matchesStructured(e, filters)) results.add(e);
            }
          }
          buf.setLength(0);
          buf.append(line);
          entryStartByte = bytePos;
          entryStartLine = rawLines;
        } else {
          if (buf.length() > 0) buf.append('\n').append(line);
        }
        bytePos += lineBytes;
      }
      // Flush the last buffered entry
      if (buf.length() > 0) {
        LogEntry e = parser.parse(buf.toString(), svcName, entryStartLine);
        if (e != null) {
          e.setFileOffset(entryStartByte);
          if (matchesStructured(e, filters)) results.add(e);
        }
      }
    }
    return results;
  }

  /** Gz-only forward scan — no byte offset tracking (cannot seek in gzip streams). */
  private List<LogEntry> scanForwardGz(
      File file, String svcName, SearchRequest req, ResolvedFilters filters) throws IOException {
    List<LogEntry> results = new ArrayList<>();
    int scanned = 0;
    int maxScan = resolveMaxScan(req);
    StringBuilder buf = new StringBuilder();
    int entryStartLine = 0;
    int rawLines = 0;

    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                new GZIPInputStream(new FileInputStream(file)), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null && scanned < maxScan) {
        rawLines++;
        if (parser.isNewEntry(line)) {
          scanned++;
          if (buf.length() > 0) {
            LogEntry e = parser.parse(buf.toString(), svcName, entryStartLine);
            if (e != null) {
              if (filters.to() != null
                  && e.getTimestamp() != null
                  && e.getTimestamp().isAfter(filters.to())) {
                return results;
              }
              if (matchesStructured(e, filters)) results.add(e);
            }
          }
          buf.setLength(0);
          buf.append(line);
          entryStartLine = rawLines;
        } else {
          if (buf.length() > 0) buf.append('\n').append(line);
        }
      }
      if (buf.length() > 0) {
        LogEntry e = parser.parse(buf.toString(), svcName, entryStartLine);
        if (e != null && matchesStructured(e, filters)) results.add(e);
      }
    }
    return results;
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Strategy 3 — BINARY_THEN_BACKWARD
  // Binary-search the file for the toTime byte position, then scan backward.
  // Best for: historical time ranges (e.g. searching 2 days ago in a 700MB file).
  //
  // Why binary search works here:
  // Log files are append-only → timestamps are monotonically increasing.
  // A monotonically ordered sequence can be binary-searched.
  // Binary search finds toTime in ~20 seeks (log2 of 3.97M lines = 21.9).
  // Compared to backward scan which would skip 3M+ recent lines to reach it.
  //
  // Binary search algorithm:
  // low = 0 (start of file)
  // high = fileSize
  // mid = (low + high) / 2
  // Read timestamp at mid position
  // If timestamp > toTime: search left half (high = mid)
  // If timestamp < toTime: search right half (low = mid)
  // Converge to the byte position where toTime first appears
  // Then hand off to scanBackward from that position
  // ────────────────────────────────────────────────────────────────────────────

  private List<LogEntry> scanBinaryThenBackward(
      File file, String svcName, SearchRequest req, ResolvedFilters filters) throws IOException {
    if (!file.exists()) return Collections.emptyList();

    // If this is a Load More request (fileOffset already set by client),
    // skip the binary search — the client already knows where to resume.
    if (req.getFileOffset() != null) {
      return scanBackward(file, svcName, req, filters);
    }

    // For backward scanning, we need to start AFTER the toTime and scan backwards
    // into the from-to window. binarySearchPositionAfter finds the first position
    // where timestamp > toTime, so backward scan walks into the window.
    long startPos = binarySearchPositionAfter(file, filters.to());
    log.info(
        "[{}] BINARY_THEN_BACKWARD: toTime={}, startPos={}, fileSize={}",
        svcName,
        filters.to(),
        startPos,
        file.length());

    // Now do a regular backward scan from that position
    SearchRequest resumeReq = cloneWithOffset(req, startPos);
    return scanBackward(file, svcName, resumeReq, filters);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Strategy 4 — BINARY_THEN_FORWARD
  // Binary-search the file for the fromTime byte position, then scan forward.
  // Best for: ASC sort with time filter (e.g. ASC + "last 5m" preset on a 700MB
  // file).
  // Without binary seek, FORWARD would scan from byte 0 of a 700MB file.
  // ────────────────────────────────────────────────────────────────────────────

  private List<LogEntry> scanBinaryThenForward(
      File file, String svcName, SearchRequest req, ResolvedFilters filters) throws IOException {
    if (!file.exists()) return Collections.emptyList();

    // If this is a Load More request (fileOffset already set by client),
    // skip the binary search — the client already knows where to resume.
    if (req.getFileOffset() != null) {
      return scanForward(file, svcName, req, filters);
    }

    long startPos = binarySearchPosition(file, filters.from());
    log.info("[{}] Binary search found fromTime position: {} bytes", svcName, startPos);

    SearchRequest resumeReq = cloneWithOffset(req, startPos);
    return scanForward(file, svcName, resumeReq, filters);
  }

  /**
   * Binary-searches the file for an approximate byte position near the targetTime boundary.
   * Converges toward the rightmost position whose timestamp is at or before targetTime, then
   * subtracts a 1 MB safety margin so a subsequent forward scan does not miss entries that straddle
   * the boundary.
   *
   * <p>Returns 0 when targetTime is null (scan from beginning), when all timestamps are after
   * targetTime, or on I/O failure.
   */
  private long binarySearchPosition(File file, LocalDateTime targetTime) {
    if (targetTime == null) return 0;

    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      long low = 0;
      long high = raf.length();
      long best = high; // fallback: start from end

      int maxIterations = 64; // log2(fileSize) — prevents infinite loop on malformed files
      int iterations = 0;

      while (low < high && iterations++ < maxIterations) {
        long mid = (low + high) / 2;

        // Seek to mid and find the next complete line from that position
        LocalDateTime ts = readTimestampNear(raf, mid);
        if (ts == null) {
          // Could not read a timestamp here — nudge right and try again
          low = mid + 1;
          continue;
        }

        if (ts.isAfter(targetTime)) {
          // This position is too recent — search left (older) half
          high = mid;
        } else {
          // This position is at or before target — record it, search right
          best = mid;
          low = mid + 1;
        }
      }

      // If best was never updated, all timestamps are after targetTime.
      // Return 0 so forward scan starts from the beginning of the file.
      if (best == raf.length()) return 0;

      // Add a safety margin: go back 1MB from best to avoid cutting a log entry in
      // half
      return Math.max(0, best - 1_048_576);

    } catch (IOException e) {
      log.warn(
          "Binary search failed for {}: {} — falling back to start of file",
          sanitizeForLog(file.getName()),
          e.getMessage());
      return 0;
    }
  }

  /**
   * Binary-searches the file for the byte position of the first log entry whose timestamp is AFTER
   * targetTime. Used by BINARY_THEN_BACKWARD — we need to start scanning backward from a position
   * just past the toTime boundary so that backward scan walks into the from-to window.
   */
  private long binarySearchPositionAfter(File file, LocalDateTime targetTime) {
    if (targetTime == null) return file.length();

    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      long low = 0;
      long high = raf.length();
      long best = high; // fallback: start from end

      int maxIterations = 64;
      int iterations = 0;

      while (low < high && iterations++ < maxIterations) {
        long mid = (low + high) / 2;

        LocalDateTime ts = readTimestampNear(raf, mid);
        if (ts == null) {
          low = mid + 1;
          continue;
        }

        if (ts.isAfter(targetTime)) {
          // This position is after target — record it, search left for closer match
          best = mid;
          high = mid;
        } else {
          // This position is at or before target — search right
          low = mid + 1;
        }
      }

      // Add a safety margin: go forward 1MB to ensure we don't miss any entries at
      // the boundary
      return Math.min(raf.length(), best + 1_048_576);

    } catch (IOException e) {
      log.warn(
          "Binary search (after) failed for {}: {} — falling back to full backward scan",
          sanitizeForLog(file.getName()),
          e.getMessage());
      return file.length();
    }
  }

  /**
   * Reads the timestamp of the first complete log line at or after bytePos. Skips partial lines at
   * the seek boundary (seeks forward to the next \n). Returns null if no parseable timestamp found
   * within 4KB of bytePos.
   */
  private LocalDateTime readTimestampNear(RandomAccessFile raf, long bytePos) throws IOException {
    raf.seek(bytePos);

    // Skip the partial line we landed in the middle of
    if (bytePos > 0) raf.readLine();

    // Read up to 200 lines looking for one with a parseable timestamp.
    // Stack traces in WES logs can exceed 50 lines, so 10 was too few —
    // the binary search returned null at many positions, causing incorrect
    // convergence.
    for (int i = 0; i < 200; i++) {
      String line = raf.readLine();
      if (line == null) return null;
      if (parser.isNewEntry(line)) {
        LogEntry e = parser.parse(line, "_binary_", 0);
        if (e != null && e.getTimestamp() != null) return e.getTimestamp();
      }
    }
    return null;
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Service status
  // ────────────────────────────────────────────────────────────────────────────

  public List<Map<String, Object>> serviceStatus() {
    return config.getServices().stream()
        .map(
            svc -> {
              Map<String, Object> s = new LinkedHashMap<>();
              File f = new File(svc.getLogFile());
              s.put("name", svc.getName());
              s.put("color", svc.getColor() != null ? svc.getColor() : "#1a6fa3");
              s.put("logFile", svc.getLogFile());
              s.put("exists", f.exists());
              s.put("sizeKb", f.exists() ? f.length() / 1024 : 0);
              s.put(
                  "lastModified",
                  f.exists() ? new java.util.Date(f.lastModified()).toString() : "N/A");
              return s;
            })
        .collect(Collectors.toList());
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Filter
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * First-pass filter: structural fields only (no free-form query). Uses precomputed
   * ResolvedFilters to avoid per-entry list allocations.
   */
  private boolean matchesStructured(LogEntry e, ResolvedFilters f) {
    if (nb(f.level()) && !f.level().equalsIgnoreCase(e.getLevel())) return false;

    if (!f.traceIds().isEmpty()) {
      if (e.getTraceId() == null) return false;
      if (!anyContains(f.traceIds(), e.getTraceId())) return false;
    }

    if (!f.spanIds().isEmpty()) {
      if (e.getSpanId() == null) return false;
      if (!anyContains(f.spanIds(), e.getSpanId())) return false;
    }

    if (nb(f.userId()) && (e.getUserId() == null || !e.getUserId().contains(f.userId())))
      return false;
    if (nb(f.siteId()) && (e.getSiteId() == null || !e.getSiteId().contains(f.siteId())))
      return false;
    if (nb(f.tenantId()) && (e.getTenantId() == null || !e.getTenantId().contains(f.tenantId())))
      return false;
    if (nb(f.logger())
        && (e.getLogger() == null
            || !e.getLogger().toLowerCase().contains(f.logger().toLowerCase()))) return false;
    if (nb(f.message())
        && (e.getMessage() == null
            || !e.getMessage().toLowerCase().contains(f.message().toLowerCase()))) return false;

    if (f.from() != null && e.getTimestamp() != null && e.getTimestamp().isBefore(f.from()))
      return false;
    if (f.to() != null && e.getTimestamp() != null && e.getTimestamp().isAfter(f.to()))
      return false;

    return true;
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ────────────────────────────────────────────────────────────────────────────

  /** Returns true if the request has any structural filters beyond time range and sort order. */
  private boolean hasStructuralFilters(SearchRequest req) {
    return nb(req.getLevel())
        || nb(req.getTraceId())
        || (req.getTraceIds() != null && !req.getTraceIds().isEmpty())
        || nb(req.getSpanId())
        || (req.getSpanIds() != null && !req.getSpanIds().isEmpty())
        || nb(req.getUserId())
        || nb(req.getSiteId())
        || nb(req.getTenantId())
        || nb(req.getLogger())
        || nb(req.getMessage())
        || nb(req.getQuery());
  }

  private List<ServiceConfig> resolveServices(List<String> names) {
    if (names == null || names.isEmpty()) return config.getServices();
    return config.getServices().stream()
        .filter(s -> names.contains(s.getName()))
        .collect(Collectors.toList());
  }

  /** Creates a copy of the request with a new fileOffset (for binary search handoff). */
  private SearchRequest cloneWithOffset(SearchRequest orig, long offset) {
    SearchRequest copy = new SearchRequest();
    copy.setServices(orig.getServices());
    copy.setLevel(orig.getLevel());
    copy.setTraceIds(orig.getTraceIds());
    copy.setSpanIds(orig.getSpanIds());
    copy.setTraceId(orig.getTraceId());
    copy.setSpanId(orig.getSpanId());
    copy.setUserId(orig.getUserId());
    copy.setSiteId(orig.getSiteId());
    copy.setTenantId(orig.getTenantId());
    copy.setMessage(orig.getMessage());
    copy.setLogger(orig.getLogger());
    copy.setQuery(orig.getQuery());
    copy.setFrom(orig.getFrom());
    copy.setTo(orig.getTo());
    copy.setLimit(orig.getLimit());
    copy.setSortOrder(orig.getSortOrder());
    copy.setFileOffset(offset);
    copy.setServiceOffsets(orig.getServiceOffsets());
    copy.setSearchId(orig.getSearchId());
    copy.setBrowserOffsetMinutes(orig.getBrowserOffsetMinutes());
    copy.setMaxScanLines(orig.getMaxScanLines());
    return copy;
  }

  private String stripCR(String s) {
    return (s != null && s.endsWith("\r")) ? s.substring(0, s.length() - 1) : s;
  }

  /**
   * Resolves effective maxScanLines: per-request override (capped by server limit) or server
   * default.
   */
  private int resolveMaxScan(SearchRequest req) {
    int serverDefault = config.getMaxScanLines();
    int serverLimit = config.getMaxScanLinesLimit();
    if (req != null && req.getMaxScanLines() != null && req.getMaxScanLines() > 0) {
      return Math.min(req.getMaxScanLines(), serverLimit);
    }
    return serverDefault;
  }

  /**
   * True when the user selected a scan depth greater than the server default (hybrid query mode).
   */
  private boolean isDeepScan(SearchRequest req) {
    return resolveMaxScan(req) > config.getMaxScanLines();
  }

  private boolean nb(String s) {
    return s != null && !s.isBlank();
  }

  // OWASP output encoding to prevent log-forging (CWE-117)
  private String sanitizeForLog(String s) {
    if (s == null) return "null";
    return Encode.forJava(s);
  }

  /**
   * Returns true if any element in the list is a substring of value. Loop avoids stream overhead.
   */
  private boolean anyContains(List<String> list, String value) {
    for (int i = 0, n = list.size(); i < n; i++) {
      if (value.contains(list.get(i))) return true;
    }
    return false;
  }

  private List<String> mergeList(List<String> list, String single) {
    List<String> result = new ArrayList<>();
    if (list != null) list.stream().filter(s -> s != null && !s.isBlank()).forEach(result::add);
    if (single != null && !single.isBlank()) {
      for (String part : single.split(",")) {
        String t = part.trim();
        if (!t.isBlank()) result.add(t);
      }
    }
    return result;
  }
}
