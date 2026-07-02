package com.honeywell.loglens.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.honeywell.loglens.model.LogEntry;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class LogParserServiceTest {

  private final LogParserService parser = new LogParserService();

  // ── Format A: ANSI colored full WES ─────────────────────────────────────────

  @Test
  void parse_formatA_ansiColoredFull() {
    String line =
        "\u001B[37m2026-03-10 21:29:42.973 host1 INFO  [user1,site1,tenant1]"
            + " [myapp,trace1,span1] 12345 --- [main] com.my.Logger : Hello world";

    LogEntry e = parser.parse(line, "svc1", 1);

    assertThat(e).isNotNull();
    assertThat(e.getTimestamp()).isEqualTo(LocalDateTime.of(2026, 3, 10, 21, 29, 42, 973_000_000));
    assertThat(e.getLevel()).isEqualTo("INFO");
    assertThat(e.getUserId()).isEqualTo("user1");
    assertThat(e.getSiteId()).isEqualTo("site1");
    assertThat(e.getTenantId()).isEqualTo("tenant1");
    assertThat(e.getTraceId()).isEqualTo("trace1");
    assertThat(e.getSpanId()).isEqualTo("span1");
    assertThat(e.getThread()).isEqualTo("main");
    assertThat(e.getLogger()).isEqualTo("com.my.Logger");
    assertThat(e.getMessage()).isEqualTo("Hello world");
    assertThat(e.getService()).isEqualTo("svc1");
    assertThat(e.getLineNumber()).isEqualTo(1);
    assertThat(e.getRawLine()).isEqualTo(line); // raw preserves ANSI codes
  }

  @Test
  void parse_formatA_multipleAnsiCodes() {
    String line =
        "\u001B[1m\u001B[31m2026-03-10 21:29:42.973 host1 ERROR [user1,site1,tenant1]"
            + " [myapp,trace1,span1] 12345 --- [main] com.my.Logger : Error occurred";

    LogEntry e = parser.parse(line, "svc1", 2);

    assertThat(e).isNotNull();
    assertThat(e.getTimestamp()).isEqualTo(LocalDateTime.of(2026, 3, 10, 21, 29, 42, 973_000_000));
    assertThat(e.getLevel()).isEqualTo("ERROR");
    assertThat(e.getUserId()).isEqualTo("user1");
    assertThat(e.getSiteId()).isEqualTo("site1");
    assertThat(e.getTenantId()).isEqualTo("tenant1");
    assertThat(e.getTraceId()).isEqualTo("trace1");
    assertThat(e.getSpanId()).isEqualTo("span1");
    assertThat(e.getThread()).isEqualTo("main");
    assertThat(e.getLogger()).isEqualTo("com.my.Logger");
    assertThat(e.getMessage()).isEqualTo("Error occurred");
    assertThat(e.getRawLine()).isEqualTo(line);
  }

  // ── Format B: plain Log4j2 (full structured, no ANSI) ──────────────────────

  @Test
  void parse_formatB_plainLog4j2() {
    // Note: "INFO " is 5 chars (%-5level pad), then space delimiter before [
    String line =
        "2026-03-10 21:29:42.973 host1 INFO  [user1,site1,tenant1]"
            + " [myapp,trace1,span1] 12345 --- [main] com.my.Logger : Hello world";

    LogEntry e = parser.parse(line, "svc1", 3);

    assertThat(e).isNotNull();
    assertThat(e.getTimestamp()).isEqualTo(LocalDateTime.of(2026, 3, 10, 21, 29, 42, 973_000_000));
    assertThat(e.getLevel()).isEqualTo("INFO");
    assertThat(e.getUserId()).isEqualTo("user1");
    assertThat(e.getSiteId()).isEqualTo("site1");
    assertThat(e.getTenantId()).isEqualTo("tenant1");
    assertThat(e.getTraceId()).isEqualTo("trace1");
    assertThat(e.getSpanId()).isEqualTo("span1");
    assertThat(e.getThread()).isEqualTo("main");
    assertThat(e.getLogger()).isEqualTo("com.my.Logger");
    assertThat(e.getMessage()).isEqualTo("Hello world");
  }

  // ── Format C: ISO-8601 timestamp (JVM startup, pre-Log4j2) ─────────────────

  @Test
  void parse_formatC_isoTimestamp() {
    String line = "2026-03-10T11:31:29.123481423Z main ERROR Unable to locate appender";

    LogEntry e = parser.parse(line, "svc1", 4);

    assertThat(e).isNotNull();
    assertThat(e.getTimestamp()).isNotNull();
    assertThat(e.getTimestamp().getYear()).isEqualTo(2026);
    assertThat(e.getTimestamp().getMonthValue()).isEqualTo(3);
    assertThat(e.getTimestamp().getDayOfMonth()).isEqualTo(10);
    assertThat(e.getTimestamp().getHour()).isEqualTo(11);
    assertThat(e.getTimestamp().getMinute()).isEqualTo(31);
    assertThat(e.getTimestamp().getSecond()).isEqualTo(29);
    assertThat(e.getTimestamp().getNano()).isEqualTo(123_481_423);
    assertThat(e.getLevel()).isEqualTo("ERROR");
    assertThat(e.getMessage()).isEqualTo("Unable to locate appender");
    assertThat(e.getService()).isEqualTo("svc1");
  }

  // ── Format D: slim (no hostname, no PID, no ---) ───────────────────────────

  @Test
  void parse_formatD_slimNoHostname() {
    // Slim format: timestamp level [userCtx] [app,traceId,spanId] [thread] logger :
    // message
    String line =
        "2026-03-10 21:29:42.973 INFO  [user1,site1,tenant1]"
            + " [myapp,trace1,span1] [main] com.my.Logger : Hello";

    LogEntry e = parser.parse(line, "svc1", 5);

    assertThat(e).isNotNull();
    assertThat(e.getTimestamp()).isEqualTo(LocalDateTime.of(2026, 3, 10, 21, 29, 42, 973_000_000));
    assertThat(e.getLevel()).isEqualTo("INFO");
    assertThat(e.getUserId()).isEqualTo("user1");
    assertThat(e.getSiteId()).isEqualTo("site1");
    assertThat(e.getTenantId()).isEqualTo("tenant1");
    assertThat(e.getTraceId()).isEqualTo("trace1");
    assertThat(e.getSpanId()).isEqualTo("span1");
    assertThat(e.getThread()).isEqualTo("main");
    assertThat(e.getLogger()).isEqualTo("com.my.Logger");
    assertThat(e.getMessage()).isEqualTo("Hello");
  }

  @Test
  void parse_formatD_emptyUserContext() {
    String line =
        "2026-03-10 21:29:42.973 INFO  []" + " [myapp,trace1,span1] [main] com.my.Logger : msg";

    LogEntry e = parser.parse(line, "svc1", 6);

    assertThat(e).isNotNull();
    assertThat(e.getLevel()).isEqualTo("INFO");
    assertThat(e.getUserId()).isNull();
    assertThat(e.getSiteId()).isNull();
    assertThat(e.getTenantId()).isNull();
    assertThat(e.getTraceId()).isEqualTo("trace1");
    assertThat(e.getSpanId()).isEqualTo("span1");
    assertThat(e.getThread()).isEqualTo("main");
    assertThat(e.getMessage()).isEqualTo("msg");
  }

  @Test
  void parse_formatD_singleUserContext() {
    String line =
        "2026-03-10 21:29:42.973 INFO  [user1]"
            + " [myapp,trace1,span1] [main] com.my.Logger : msg";

    LogEntry e = parser.parse(line, "svc1", 7);

    assertThat(e).isNotNull();
    assertThat(e.getLevel()).isEqualTo("INFO");
    assertThat(e.getUserId()).isEqualTo("user1");
    assertThat(e.getSiteId()).isNull();
    assertThat(e.getTenantId()).isNull();
    assertThat(e.getTraceId()).isEqualTo("trace1");
    assertThat(e.getSpanId()).isEqualTo("span1");
  }

  @Test
  void parse_formatD_tripleUserContext() {
    String line =
        "2026-03-10 21:29:42.973 INFO  [user1,site1,tenant1]"
            + " [myapp,trace1,span1] [main] com.my.Logger : msg";

    LogEntry e = parser.parse(line, "svc1", 8);

    assertThat(e).isNotNull();
    assertThat(e.getLevel()).isEqualTo("INFO");
    assertThat(e.getUserId()).isEqualTo("user1");
    assertThat(e.getSiteId()).isEqualTo("site1");
    assertThat(e.getTenantId()).isEqualTo("tenant1");
    assertThat(e.getTraceId()).isEqualTo("trace1");
    assertThat(e.getSpanId()).isEqualTo("span1");
  }

  // ── Format E: slim with {:} brace separator ─────────────────────────────────

  @Test
  void parse_formatE_braceSeparator() {
    // Real line from momentumconnect-console2.log
    String line =
        "2026-04-15 02:03:44.282 ERROR [] [momentumconnect,,]"
            + " [SST /10.121.4.200:47714 via 14001 248]"
            + " c.i.c.m.r.RabbitMQTopicProducer {:} Unable to establish connection.";

    LogEntry e = parser.parse(line, "mc", 1);

    assertThat(e).isNotNull();
    assertThat(e.getTimestamp()).isEqualTo(LocalDateTime.of(2026, 4, 15, 2, 3, 44, 282_000_000));
    assertThat(e.getLevel()).isEqualTo("ERROR");
    assertThat(e.getUserId()).isNull();
    assertThat(e.getSiteId()).isNull();
    assertThat(e.getTenantId()).isNull();
    assertThat(e.getTraceId()).isNull();
    assertThat(e.getSpanId()).isNull();
    assertThat(e.getThread()).isEqualTo("SST /10.121.4.200:47714 via 14001 248");
    assertThat(e.getLogger()).isEqualTo("c.i.c.m.r.RabbitMQTopicProducer");
    assertThat(e.getMessage()).isEqualTo("Unable to establish connection.");
  }

  @Test
  void parse_formatE_braceSeparatorWarn() {
    String line =
        "2026-04-15 02:03:44.731 WARN  [] [momentumconnect,,]"
            + " [heartbeat-tcpCF_panda_label_request_6]"
            + " c.h.i.w.m.d.HeartbeatSender {:} Heartbeat failed.";

    LogEntry e = parser.parse(line, "mc", 57);

    assertThat(e).isNotNull();
    assertThat(e.getLevel()).isEqualTo("WARN");
    assertThat(e.getThread()).isEqualTo("heartbeat-tcpCF_panda_label_request_6");
    assertThat(e.getLogger()).isEqualTo("c.h.i.w.m.d.HeartbeatSender");
    assertThat(e.getMessage()).isEqualTo("Heartbeat failed.");
  }

  @Test
  void parse_formatE_braceSeparatorWithStackTrace() {
    String block =
        "2026-04-15 02:03:44.282 ERROR [] [momentumconnect,,]"
            + " [SST /10.121.4.200:47714 via 14001 248]"
            + " c.i.c.m.r.RabbitMQTopicProducer {:} Unable to establish connection.\n"
            + "java.net.ConnectException: Connection refused\n"
            + "\tat java.base/sun.nio.ch.Net.pollConnect(Native Method) ~[?:?]";

    LogEntry e = parser.parse(block, "mc", 1);

    assertThat(e).isNotNull();
    assertThat(e.getLevel()).isEqualTo("ERROR");
    assertThat(e.getLogger()).isEqualTo("c.i.c.m.r.RabbitMQTopicProducer");
    assertThat(e.getMessage()).isEqualTo("Unable to establish connection.");
    assertThat(e.hasStackTrace()).isTrue();
    assertThat(e.getStackTrace()).contains("ConnectException");
  }

  // ── Edge cases ──────────────────────────────────────────────────────────────

  @Test
  void parse_nullLine_returnsNull() {
    assertThat(parser.parse(null, "svc1", 1)).isNull();
  }

  @Test
  void parse_emptyLine_returnsNull() {
    assertThat(parser.parse("", "svc1", 1)).isNull();
    assertThat(parser.parse("   ", "svc1", 1)).isNull();
  }

  @Test
  void parse_continuationLine_returnsNull() {
    // Stack trace continuation lines passed alone to parse() — not blank, so the
    // raw fallback fires: level "UNKNOWN", no timestamp, message = the raw line.
    // In normal usage, continuation lines are appended to their parent block and
    // never reach parse() independently.
    LogEntry e = parser.parse("    at com.foo.Bar.method(Bar.java:42)", "svc1", 1);

    assertThat(e).isNotNull();
    assertThat(e.getLevel()).isEqualTo("UNKNOWN");
    assertThat(e.getTimestamp()).isNull();
    assertThat(e.getMessage()).isEqualTo("    at com.foo.Bar.method(Bar.java:42)");
  }

  @Test
  void parse_messageWithHtmlChars() {
    String line =
        "2026-03-10 21:29:42.973 host1 INFO  [user1,site1,tenant1]"
            + " [myapp,trace1,span1] 12345 --- [main] com.my.Logger : <script>alert(1)</script>";

    LogEntry e = parser.parse(line, "svc1", 1);

    assertThat(e).isNotNull();
    assertThat(e.getMessage()).isEqualTo("<script>alert(1)</script>");
  }

  @Test
  void parse_messageWithQuotes() {
    String line =
        "2026-03-10 21:29:42.973 host1 INFO  [user1,site1,tenant1]"
            + " [myapp,trace1,span1] 12345 --- [main] com.my.Logger"
            + " : He said \"hello\" and she said 'hi'";

    LogEntry e = parser.parse(line, "svc1", 1);

    assertThat(e).isNotNull();
    assertThat(e.getMessage()).isEqualTo("He said \"hello\" and she said 'hi'");
  }

  @Test
  void parse_veryLongMessage() {
    String longMsg = "X".repeat(10_240); // 10 KB message
    String line =
        "2026-03-10 21:29:42.973 host1 INFO  [user1,site1,tenant1]"
            + " [myapp,trace1,span1] 12345 --- [main] com.my.Logger : "
            + longMsg;

    LogEntry e = parser.parse(line, "svc1", 1);

    assertThat(e).isNotNull();
    assertThat(e.getMessage()).isEqualTo(longMsg);
    assertThat(e.getMessage()).hasSize(10_240);
  }

  @Test
  void parse_allFieldsBlank() {
    // All MDC brackets empty — only timestamp, level, thread, logger survive
    String line = "2026-03-10 21:29:42.973 host1 INFO  [,,] [,,] 12345 --- [main] c.m.Logger : ";

    LogEntry e = parser.parse(line, "svc1", 1);

    assertThat(e).isNotNull();
    assertThat(e.getTimestamp()).isEqualTo(LocalDateTime.of(2026, 3, 10, 21, 29, 42, 973_000_000));
    assertThat(e.getLevel()).isEqualTo("INFO");
    assertThat(e.getUserId()).isNull();
    assertThat(e.getSiteId()).isNull();
    assertThat(e.getTenantId()).isNull();
    assertThat(e.getTraceId()).isNull();
    assertThat(e.getSpanId()).isNull();
    assertThat(e.getMessage()).isNull();
    assertThat(e.getThread()).isEqualTo("main");
    assertThat(e.getLogger()).isEqualTo("c.m.Logger");
  }

  @Test
  void parse_windowsLineEndings() {
    String line =
        "2026-03-10 21:29:42.973 host1 INFO  [user1,site1,tenant1]"
            + " [myapp,trace1,span1] 12345 --- [main] com.my.Logger : Hello world\r\n";

    LogEntry e = parser.parse(line, "svc1", 1);

    assertThat(e).isNotNull();
    assertThat(e.getLevel()).isEqualTo("INFO");
    assertThat(e.getMessage()).isEqualTo("Hello world");
    assertThat(e.getStackTrace()).isNull();
  }

  // ── isNewEntry tests ────────────────────────────────────────────────────────

  @Test
  void isNewEntry_plainDate_true() {
    assertThat(
            parser.isNewEntry("2026-03-10 21:29:42.973 INFO  [user1,site1,tenant1] rest of line"))
        .isTrue();
  }

  @Test
  void isNewEntry_isoDate_true() {
    assertThat(
            parser.isNewEntry(
                "2026-03-10T11:31:29.123481423Z main ERROR Unable to locate appender"))
        .isTrue();
  }

  @Test
  void isNewEntry_ansiDate_true() {
    // Single ANSI code prefix — ESC[37m is 5 chars, then date starts
    assertThat(parser.isNewEntry("\u001B[37m2026-03-10 21:29:42.973 INFO  something here"))
        .isTrue();
  }

  @Test
  void isNewEntry_multiAnsi_true() {
    // Regression test for fix C3: multiple ANSI codes before the date
    assertThat(parser.isNewEntry("\u001B[1m\u001B[31m2026-03-10 21:29:42.973 ERROR something here"))
        .isTrue();
  }

  @Test
  void isNewEntry_continuationLine_false() {
    assertThat(parser.isNewEntry("    at com.foo.Bar.method(Bar.java:42)")).isFalse();
  }

  @Test
  void isNewEntry_nullLine_false() {
    assertThat(parser.isNewEntry(null)).isFalse();
  }

  @Test
  void isNewEntry_shortLine_false() {
    assertThat(parser.isNewEntry("abc")).isFalse();
  }

  @Test
  void isNewEntry_almostDate_false() {
    // Position 19 has ':' instead of '.' — close to a valid timestamp but not quite
    assertThat(parser.isNewEntry("2026-03-10 21:29:42:973 INFO  something here")).isFalse();
  }

  // ── Multiline / stack trace parsing ─────────────────────────

  @Test
  void parse_multiline_extractsStackTrace() {
    String multiline =
        "2026-03-10 21:29:42.973 host1 ERROR [user1,site1,tenant1] [app,trace001,span001] 12345 --- [main] com.test.Logger : Something failed\n"
            + "java.lang.NullPointerException: null\n"
            + "    at com.foo.Bar.method(Bar.java:42)\n"
            + "    at com.foo.Baz.run(Baz.java:10)";
    LogEntry e = parser.parse(multiline, "app", 1);

    assertThat(e).isNotNull();
    assertThat(e.getMessage()).isEqualTo("Something failed");
    assertThat(e.getStackTrace()).contains("NullPointerException");
    assertThat(e.getStackTrace()).contains("at com.foo.Bar.method");
    assertThat(e.hasStackTrace()).isTrue();
  }
}
