package com.honeywell.loglens.controller;

import com.honeywell.loglens.model.LogEntry;
import com.honeywell.loglens.model.SearchRequest;
import com.honeywell.loglens.service.LogSearchService;
import com.honeywell.loglens.service.LogSearchService.ExportData;
import com.honeywell.loglens.service.LogSearchService.FileInfo;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.owasp.encoder.Encode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogController {

  private static final Pattern SAFE_NAME = Pattern.compile("[a-zA-Z0-9._-]+");
  private static final Pattern SAFE_ID = Pattern.compile("[a-zA-Z0-9_-]+");

  private final LogSearchService searchService;

  @PostMapping("/search")
  public ResponseEntity<?> search(@RequestBody SearchRequest req) {
    if (req == null) {
      return ResponseEntity.badRequest().body(Map.of("error", "Request body is required"));
    }
    try {
      if (req.getSearchId() != null && !SAFE_ID.matcher(req.getSearchId()).matches()) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid searchId"));
      }
      return ResponseEntity.ok(searchService.search(req));
    } catch (IllegalArgumentException e) {
      log.warn("Invalid search request", e);
      return ResponseEntity.badRequest().body(Map.of("error", "Invalid search request"));
    } catch (Exception e) {
      log.error("Search failed", e);
      return ResponseEntity.internalServerError().body(Map.of("error", "Search failed"));
    }
  }

  @GetMapping("/services")
  public ResponseEntity<List<Map<String, Object>>> services() {
    return ResponseEntity.ok(searchService.serviceStatus());
  }

  /**
   * Returns the server's current local time as a string. The browser uses this to calculate the
   * delta between browser time and server time, so time presets (5m, 15m etc.) always align with
   * log timestamps regardless of timezone differences.
   */
  @GetMapping("/servertime")
  public ResponseEntity<Map<String, String>> serverTime() {
    String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    return ResponseEntity.ok(Map.of("serverTime", now));
  }

  @GetMapping("/metrics")
  public ResponseEntity<Map<String, Object>> metrics() {
    Map<String, Object> m = new LinkedHashMap<>();

    // ── OS-level metrics ─────────────────────────────────────────
    com.sun.management.OperatingSystemMXBean osBean =
        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    m.put("cpuSystem", Math.round(osBean.getCpuLoad() * 1000.0) / 10.0); // % with 1 decimal
    m.put("cpuProcess", Math.round(osBean.getProcessCpuLoad() * 1000.0) / 10.0);
    m.put("memTotalMB", osBean.getTotalMemorySize() / (1024 * 1024));
    m.put("memUsedMB", (osBean.getTotalMemorySize() - osBean.getFreeMemorySize()) / (1024 * 1024));

    // ── JVM heap ─────────────────────────────────────────────────
    Runtime rt = Runtime.getRuntime();
    m.put("heapUsedMB", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
    m.put("heapMaxMB", rt.maxMemory() / (1024 * 1024));

    // ── Threads ──────────────────────────────────────────────────
    ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    m.put("threadCount", threadBean.getThreadCount());

    // ── GC stats ─────────────────────────────────────────────────
    long gcCount = 0, gcTimeMs = 0;
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      gcCount += gc.getCollectionCount();
      gcTimeMs += gc.getCollectionTime();
    }
    m.put("gcCount", gcCount);
    m.put("gcTimeMs", gcTimeMs);

    // ── Uptime ───────────────────────────────────────────────────
    long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
    Duration d = Duration.ofMillis(uptimeMs);
    m.put("uptime", String.format("%dd %dh %dm", d.toDays(), d.toHoursPart(), d.toMinutesPart()));

    // ── Cache / request stats from LogSearchService ──────────────
    m.putAll(searchService.cacheMetrics());

    return ResponseEntity.ok(m);
  }

  @GetMapping("/health")
  public ResponseEntity<Map<String, String>> health() {
    return ResponseEntity.ok(Map.of("status", "UP", "app", "loglens"));
  }

  /** Lists archived/historical log files in a service's log directory. */
  @GetMapping("/services/{serviceName}/files")
  public ResponseEntity<List<FileInfo>> listFiles(@PathVariable String serviceName) {
    return ResponseEntity.ok(searchService.listServiceFiles(serviceName));
  }

  /** Searches a single historical/archived log file. */
  @PostMapping("/search/historical")
  public ResponseEntity<?> searchHistorical(
      @RequestParam String serviceName,
      @RequestParam String fileName,
      @RequestBody SearchRequest req) {
    if (!SAFE_NAME.matcher(serviceName).matches()) {
      return ResponseEntity.badRequest().body(Map.of("error", "Invalid service name"));
    }
    if (!SAFE_NAME.matcher(fileName).matches()) {
      return ResponseEntity.badRequest().body(Map.of("error", "Invalid file name"));
    }
    if (req == null) {
      return ResponseEntity.badRequest().body(Map.of("error", "Request body is required"));
    }
    if (req.getSearchId() != null && !SAFE_ID.matcher(req.getSearchId()).matches()) {
      return ResponseEntity.badRequest().body(Map.of("error", "Invalid searchId"));
    }
    try {
      return ResponseEntity.ok(searchService.searchHistorical(serviceName, fileName, req));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (IOException e) {
      log.error(
          "Historical search failed for serviceName={} fileName={}",
          Encode.forJava(serviceName),
          Encode.forJava(fileName),
          e);
      return ResponseEntity.internalServerError()
          .body(Map.of("error", "Search failed due to an internal I/O error."));
    }
  }

  /** Streams filtered log entries as TXT or CSV download. */
  @GetMapping("/export")
  public void export(
      @RequestParam String searchId,
      @RequestParam(defaultValue = "txt") String format,
      HttpServletResponse response)
      throws IOException {
    if (!SAFE_ID.matcher(searchId).matches()) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      response.setContentType("application/json; charset=UTF-8");
      response.getWriter().write("{\"error\":\"Invalid searchId\"}");
      return;
    }
    ExportData data = searchService.getExportEntries(searchId);
    if (data == null) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      response.setContentType("application/json; charset=UTF-8");
      response.getWriter().write("{\"error\":\"Session expired or not found. Run a new search.\"}");
      return;
    }

    String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    String ext = "csv".equalsIgnoreCase(format) ? "csv" : "txt";
    String safeSortOrder =
        data.sortOrder() != null ? data.sortOrder().replaceAll("[^a-zA-Z]", "") : "desc";
    String filename = "loglens-" + ts + "-" + safeSortOrder + "." + ext;

    response.setCharacterEncoding("UTF-8");
    response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

    if ("csv".equalsIgnoreCase(format)) {
      response.setContentType("text/csv; charset=UTF-8");
      writeCsv(response.getWriter(), data.entries());
    } else {
      response.setContentType("text/plain; charset=UTF-8");
      writeTxt(response.getWriter(), data.entries());
    }
  }

  private void writeTxt(PrintWriter w, List<LogEntry> entries) {
    for (int i = 0; i < entries.size(); i++) {
      LogEntry e = entries.get(i);
      String line = e.getRawLine();
      if (line != null && !line.isBlank()) {
        w.print(line);
      } else {
        // Fallback: reconstruct from structured fields
        w.print(
            e.getTimestamp()
                + " "
                + e.getLevel()
                + " ["
                + (e.getThread() != null ? e.getThread() : "")
                + "] "
                + (e.getLogger() != null ? e.getLogger() : "")
                + " - "
                + (e.getMessage() != null ? e.getMessage() : ""));
        if (e.hasStackTrace()) {
          w.println();
          w.print(e.getStackTrace());
        }
      }
      if (i < entries.size() - 1) {
        w.println();
        w.println();
      }
    }
    w.flush();
  }

  private static final String[] CSV_HEADERS = {
    "Timestamp", "Level", "Service", "Logger", "Thread",
    "TraceId", "SpanId", "UserId", "SiteId", "TenantId",
    "Message", "StackTrace"
  };

  private static final DateTimeFormatter CSV_TS_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

  private void writeCsv(PrintWriter w, List<LogEntry> entries) {
    // UTF-8 BOM for Excel compatibility
    w.print('\uFEFF');
    w.println(String.join(",", CSV_HEADERS));
    for (LogEntry e : entries) {
      w.print(csvField(e.getTimestamp() != null ? e.getTimestamp().format(CSV_TS_FMT) : ""));
      w.print(',');
      w.print(csvField(e.getLevel()));
      w.print(',');
      w.print(csvField(e.getService()));
      w.print(',');
      w.print(csvField(e.getLogger()));
      w.print(',');
      w.print(csvField(e.getThread()));
      w.print(',');
      w.print(csvField(e.getTraceId()));
      w.print(',');
      w.print(csvField(e.getSpanId()));
      w.print(',');
      w.print(csvField(e.getUserId()));
      w.print(',');
      w.print(csvField(e.getSiteId()));
      w.print(',');
      w.print(csvField(e.getTenantId()));
      w.print(',');
      w.print(csvField(e.getMessage()));
      w.print(',');
      w.print(csvField(e.getStackTrace()));
      w.println();
    }
    w.flush();
  }

  /** RFC 4180 CSV field escaping with formula injection mitigation. */
  private String csvField(String val) {
    if (val == null) return "";
    // Prevent CSV injection: prefix formula-triggering characters with a tab
    if (!val.isEmpty() && "=+-@".indexOf(val.charAt(0)) >= 0) {
      val = "\t" + val;
    }
    if (val.indexOf(',') >= 0
        || val.indexOf('"') >= 0
        || val.indexOf('\n') >= 0
        || val.indexOf('\r') >= 0) {
      return '"' + val.replace("\"", "\"\"") + '"';
    }
    return val;
  }

  /**
   * Clears all in-memory search caches. Intentionally unauthenticated — this is an internal
   * diagnostic tool with no Spring Security; all endpoints are open. If auth is added later,
   * restrict this to admin role.
   */
  @PostMapping("/cache/clear")
  public ResponseEntity<Map<String, String>> clearCache() {
    searchService.clearAllCaches();
    return ResponseEntity.ok(Map.of("status", "cleared"));
  }
}
