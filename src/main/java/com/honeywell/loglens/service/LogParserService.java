package com.honeywell.loglens.service;

import com.honeywell.loglens.model.LogEntry;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Parses log lines produced by WES Log4j2 patterns.
 *
 * <p>Five formats found in the wild:
 *
 * <p>FORMAT A/B — Full WES Log4j2 (with or without ANSI colour codes): TIMESTAMP HOSTNAME LEVEL
 * [userId,siteId,tenantId] [app,traceId,spanId] PID --- [thread] logger : message
 *
 * <p>FORMAT D — Slim WES Log4j2 (no hostname, no PID, no ---): TIMESTAMP LEVEL [userContext]
 * [app,traceId,spanId] [thread] logger : message User context bracket may be [] (empty), [user]
 * (single), or [user,site,tenant] (triple).
 *
 * <p>FORMAT E — Slim WES Log4j2 with brace separator ({:} instead of :): Same structure as Format D
 * but logger-message separator is " {:} ". Found on certain MomentumConnect deployments.
 *
 * <p>FORMAT C — ISO-8601 timestamps (JVM startup lines, before Log4j2 initialises):
 * 2026-03-10T11:31:29.123481423Z main ERROR Unable to locate appender "LogToFile" ...
 *
 * <p>Key behaviours: - ANSI escape codes are stripped before parsing - Thread names may have double
 * closing bracket [thread]] — handled - message is extracted from first line only; stack trace is
 * everything after - rawLine stores the FULL block including stack trace, never truncated
 */
@Service
@Slf4j
public class LogParserService {

  // ANSI escape sequence: ESC [ ... <letter> (SGR, cursor movement, erase, etc.)
  private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[A-Za-z]");

  // ── Timestamp formatters ────────────────────────────────────────────────────
  private static final DateTimeFormatter TS_SPACE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

  // ── FORMAT A & B: full structured Log4j2 line (after ANSI stripped) ─────────
  //
  // Covers both coloured (A) and plain (B) — they are identical after ANSI strip.
  //
  // Example plain:
  // 2026-03-10 21:29:42.973 IE4LLT9F6RS54 INFO [,,]
  // [com-honeywell-intelligrated-wes-routing,,]
  // 41644 --- [ main] c.u.j.c.EnableEncryptablePropertiesBeanFactoryPostProcessor
  // :
  // Post-processing ...
  //
  // Example coloured (after strip):
  // 2026-03-10 11:31:29.176 cs-app-vm INFO [,,]
  // [com-honeywell-intelligrated-wes-routing,,]
  // 3048916 --- [kground-preinit] o.h.v.i.u.Version : HV000001: Hibernate ...
  //
  // Thread group: [^\[]+ consumes everything that is not '[', then \]+ eats one
  // or more ']'
  // This handles the double-bracket anomaly [ultJobExecutor]] from Log4j2 %15.15t
  private static final Pattern FULL =
      Pattern.compile(
          "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})"
              + // 1: timestamp
              "\\s+(\\S+)"
              + // 2: hostname
              "\\s+(ERROR|WARN |INFO |DEBUG|TRACE)"
              + // 3: level (padded to 5)
              "\\s+\\[([^,\\]]*),([^,\\]]*),([^\\]]*)\\]"
              + // 4,5,6: userId, siteId, tenantId
              "\\s+\\[([^,\\]]*),([^,\\]]*),([^\\]]*)\\]"
              + // 7,8,9: service, traceId, spanId
              "\\s+\\S+"
              + // PID (not captured)
              "\\s+---"
              + "\\s+\\[([^\\[]+)\\]+"
              + // 10: thread (greedy — handles double ]])
              "\\s+(\\S+)"
              + // 11: logger
              "\\s+\\{?:\\}?\\s+(.*)", // 12: message (accepts both " : " and " {:} ")
          Pattern.DOTALL);

  // ── FORMAT D: slim Log4j2 (no hostname, no PID, no ---) ──────────────────
  //
  // Found on newer WES servers. After ANSI strip:
  // 2026-03-31 11:22:54.784 INFO [] [routing,,] [background-preinit]
  // o.h.v.i.u.Version : message
  // 2026-03-31 13:21:20.150 DEBUG [redsUser] [momentumconnect,trace,span]
  // [pool-48-thread-2] logger : message
  // 2026-03-31 13:21:20.129 DEBUG [redsUser] [momentumconnect,d547...,93cc...]
  // [pool-49-thread-2] logger : message
  //
  // User context bracket: [] or [user] or [user,site,tenant]
  // App bracket: [app,traceId,spanId] (always 3 parts)
  private static final Pattern SLIM =
      Pattern.compile(
          "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})"
              + // 1: timestamp
              "\\s+(ERROR|WARN |INFO |DEBUG|TRACE)"
              + // 2: level (padded to 5)
              "\\s+\\[([^\\]]*)\\]"
              + // 3: user context (may be empty, "user", or "user,site,tenant")
              "\\s+\\[([^,\\]]*),([^,\\]]*),([^\\]]*)\\]"
              + // 4,5,6: app, traceId, spanId
              "\\s+\\[([^\\[]+)\\]+"
              + // 7: thread (handles double ]])
              "\\s+(\\S+)"
              + // 8: logger
              "\\s+\\{?:\\}?\\s+(.*)", // 9: message (accepts both " : " and " {:} ")
          Pattern.DOTALL);

  // ── Fallback: timestamp + level + rest (with or without hostname) ───────────
  // Catches startup banner lines, lines with non-standard MDC content, etc.
  private static final Pattern SIMPLE =
      Pattern.compile(
          "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})"
              + "(?:\\s+\\S+)?"
              + // optional hostname
              "\\s+(ERROR|WARN|INFO|DEBUG|TRACE)\\s+(.*)",
          Pattern.DOTALL);

  // ── FORMAT C: ISO-8601 (JVM startup before Log4j2 initialises) ──────────────
  // Example: 2026-03-10T11:31:29.123481423Z main ERROR Unable to locate appender
  // ...
  private static final Pattern ISO =
      Pattern.compile(
          "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z?)"
              + "\\s+\\S+"
              + // thread (e.g. "main")
              "\\s+(ERROR|WARN|INFO|DEBUG|TRACE)"
              + "\\s+(.*)",
          Pattern.DOTALL);

  // ── NEW ENTRY DETECTION ─────────────────────────────────────────────────────

  /**
   * Fast O(1) check — does this line start a new log entry?
   *
   * <p>Handles all five formats: Format A (ANSI): line may start with ESC[ codes before the date
   * Format B (plain): "2026-03-10 21:29:42.973 ..." char[10] = ' ' Format C (ISO):
   * "2026-03-10T11:31:29.123..." char[10] = 'T' Format D/E (slim): same date prefix as B, detected
   * by isNewEntry identically
   *
   * <p>For ANSI lines, we skip past the leading escape sequence to find the date.
   */
  public boolean isNewEntry(String line) {
    if (line == null || line.length() < 23) return false;

    // Find the start of the date — skip ALL leading ANSI codes (\x1B[...m)
    int start = 0;
    while (start < line.length() && line.charAt(start) == '\u001B') {
      int m = line.indexOf('m', start);
      if (m < 0) return false;
      start = m + 1;
    }

    if (start + 23 > line.length()) return false;
    if (!Character.isDigit(line.charAt(start))) return false;
    if (line.charAt(start + 4) != '-') return false;
    if (line.charAt(start + 7) != '-') return false;

    char sep = line.charAt(start + 10);
    if (sep != ' ' && sep != 'T') return false;

    return line.charAt(start + 13) == ':'
        && line.charAt(start + 16) == ':'
        && line.charAt(start + 19) == '.';
  }

  // ── PARSING ─────────────────────────────────────────────────────────────────

  /**
   * Parse a complete log block (first line + any stack trace continuation lines). message = text
   * after " : " on the first line only rawLine = ENTIRE block, never truncated
   */
  public LogEntry parse(String block, String serviceName, int lineNumber) {
    if (block == null || block.isBlank()) return null;

    int nl = block.indexOf('\n');
    String firstLine = nl >= 0 ? block.substring(0, nl) : block;
    String stackTrace = nl >= 0 ? block.substring(nl + 1).stripTrailing() : null;

    // Strip ANSI codes from first line before regex matching
    String cleanLine = ANSI.matcher(firstLine).replaceAll("");

    LogEntry.LogEntryBuilder b =
        LogEntry.builder()
            .service(serviceName)
            .rawLine(block) // keep original with ANSI for display
            .stackTrace(stackTrace != null && !stackTrace.isBlank() ? stackTrace : null)
            .lineNumber(lineNumber);

    // ── Try FULL pattern (Format A & B after strip) ──────────────────────────
    Matcher m = FULL.matcher(cleanLine);
    if (m.matches()) {
      return b.timestamp(parseSpaceTs(m.group(1)))
          .level(m.group(3).trim())
          .userId(clean(m.group(4)))
          .siteId(clean(m.group(5)))
          .tenantId(clean(m.group(6)))
          .traceId(clean(m.group(8)))
          .spanId(clean(m.group(9)))
          .thread(clean(m.group(10)))
          .logger(clean(m.group(11)))
          .message(clean(m.group(12)))
          .build();
    }

    // ── Try SLIM pattern (Format D — no hostname/PID/---) ─────────────────────
    Matcher dm = SLIM.matcher(cleanLine);
    if (dm.matches()) {
      // Parse user context: "" or "user" or "user,site,tenant"
      String userCtx = dm.group(3);
      String userId = null, siteId = null, tenantId = null;
      if (userCtx != null && !userCtx.isEmpty()) {
        String[] parts = userCtx.split(",", 3);
        userId = clean(parts[0]);
        if (parts.length > 1) siteId = clean(parts[1]);
        if (parts.length > 2) tenantId = clean(parts[2]);
      }
      return b.timestamp(parseSpaceTs(dm.group(1)))
          .level(dm.group(2).trim())
          .userId(userId)
          .siteId(siteId)
          .tenantId(tenantId)
          .traceId(clean(dm.group(5)))
          .spanId(clean(dm.group(6)))
          .thread(clean(dm.group(7)))
          .logger(clean(dm.group(8)))
          .message(clean(dm.group(9)))
          .build();
    }

    // ── Try SIMPLE fallback (Format A & B — startup banners etc.) ────────────
    Matcher sm = SIMPLE.matcher(cleanLine);
    if (sm.matches()) {
      return b.timestamp(parseSpaceTs(sm.group(1)))
          .level(sm.group(2).trim())
          .message(clean(sm.group(3)))
          .build();
    }

    // ── Try ISO format (Format C — JVM pre-Log4j2 lines) ─────────────────────
    Matcher im = ISO.matcher(cleanLine);
    if (im.matches()) {
      return b.timestamp(parseIsoTs(im.group(1)))
          .level(im.group(2).trim())
          .message(clean(im.group(3)))
          .build();
    }

    // ── Raw fallback — unparseable but still returned ─────────────────────────
    return b.level("UNKNOWN").message(cleanLine).build();
  }

  // ── TIMESTAMP PARSING ───────────────────────────────────────────────────────

  private LocalDateTime parseSpaceTs(String ts) {
    try {
      return LocalDateTime.parse(ts, TS_SPACE);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * Parse ISO-8601 timestamps with variable sub-second precision and optional Z. Examples:
   * "2026-03-10T11:31:29.123481423Z" (nanoseconds) "2026-03-10T11:31:29.302650709Z" (nanoseconds)
   * "2026-03-10T11:31:30.302Z" (millis) Strategy: strip Z, truncate sub-second to at most 9 digits,
   * parse with nano formatter.
   */
  private LocalDateTime parseIsoTs(String ts) {
    String s = ts.endsWith("Z") ? ts.substring(0, ts.length() - 1) : ts;
    int dot = s.indexOf('.');
    if (dot > 0) {
      String intPart = s.substring(0, dot + 1); // "2026-03-10T11:31:29."
      String fracPart = s.substring(dot + 1); // "123481423"
      if (fracPart.length() > 9) fracPart = fracPart.substring(0, 9);
      // Pad to 9 digits so DateTimeFormatter("n") works correctly
      while (fracPart.length() < 9) fracPart += "0";
      s = intPart + fracPart;
    }
    try {
      return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.n"));
    } catch (DateTimeParseException e) {
      // Final fallback: truncate to millis
      try {
        if (dot > 0 && s.length() > dot + 4) s = s.substring(0, dot + 4);
        return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"));
      } catch (DateTimeParseException ex) {
        return null;
      }
    }
  }

  // ── HELPERS ─────────────────────────────────────────────────────────────────

  private String clean(String s) {
    if (s == null) return null;
    s = s.trim();
    return (s.isEmpty() || s.equals("-") || s.equals("null") || s.equals("N/A")) ? null : s;
  }
}
