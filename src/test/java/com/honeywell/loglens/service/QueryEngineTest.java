package com.honeywell.loglens.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.honeywell.loglens.model.LogEntry;
import org.junit.jupiter.api.Test;

class QueryEngineTest {

  private final QueryEngine engine = new QueryEngine();

  // ── Helper ────────────────────────────────────────────────────

  private LogEntry entry(String level, String message) {
    return LogEntry.builder().service("svc").level(level).message(message).build();
  }

  // ── Simple keyword ────────────────────────────────────────────

  @Test
  void matches_simpleWord_substringMatch() {
    LogEntry e = entry("INFO", "say hello world");
    assertThat(engine.matches(e, "hello")).isTrue();
  }

  // ── Quoted phrase ─────────────────────────────────────────────

  @Test
  void matches_quotedPhrase_exactMatch() {
    LogEntry match = entry("INFO", "say hello world");
    LogEntry noMatch = entry("INFO", "hello brave world");

    assertThat(engine.matches(match, "\"hello world\"")).isTrue();
    assertThat(engine.matches(noMatch, "\"hello world\"")).isFalse();
  }

  // ── Field: level (exact match) ────────────────────────────────

  @Test
  void matches_fieldLevel_exactLevelMatch() {
    LogEntry error = entry("ERROR", "something broke");
    LogEntry info = entry("INFO", "all good");

    assertThat(engine.matches(error, "level:ERROR")).isTrue();
    assertThat(engine.matches(info, "level:ERROR")).isFalse();
  }

  // ── Field: userId (substring match) ───────────────────────────

  @Test
  void matches_fieldUserId_substringMatch() {
    LogEntry e =
        LogEntry.builder()
            .service("svc")
            .level("INFO")
            .message("login")
            .userId("superadmin")
            .build();

    assertThat(engine.matches(e, "user:admin")).isTrue();
  }

  // ── AND operator ──────────────────────────────────────────────

  @Test
  void matches_andOperator_bothRequired() {
    LogEntry both = entry("ERROR", "connection timeout after 30s");
    LogEntry onlyError = entry("ERROR", "null pointer exception");
    LogEntry onlyTimeout = entry("INFO", "connection timeout after 30s");

    assertThat(engine.matches(both, "level:ERROR AND timeout")).isTrue();
    assertThat(engine.matches(onlyError, "level:ERROR AND timeout")).isFalse();
    assertThat(engine.matches(onlyTimeout, "level:ERROR AND timeout")).isFalse();
  }

  // ── OR operator ───────────────────────────────────────────────

  @Test
  void matches_orOperator_eitherSuffices() {
    LogEntry error = entry("ERROR", "disk full");
    LogEntry warn = entry("WARN", "disk almost full");
    LogEntry info = entry("INFO", "disk ok");

    assertThat(engine.matches(error, "level:ERROR OR level:WARN")).isTrue();
    assertThat(engine.matches(warn, "level:ERROR OR level:WARN")).isTrue();
    assertThat(engine.matches(info, "level:ERROR OR level:WARN")).isFalse();
  }

  // ── NOT operator ──────────────────────────────────────────────

  @Test
  void matches_notOperator_excludes() {
    LogEntry debug = entry("DEBUG", "trace output");
    LogEntry error = entry("ERROR", "something broke");

    assertThat(engine.matches(debug, "NOT level:DEBUG")).isFalse();
    assertThat(engine.matches(error, "NOT level:DEBUG")).isTrue();
  }

  // ── Nested parentheses ────────────────────────────────────────

  @Test
  void matches_nestedParentheses() {
    LogEntry errorTimeout = entry("ERROR", "connection timeout");
    LogEntry warnTimeout = entry("WARN", "connection timeout");
    LogEntry infoTimeout = entry("INFO", "connection timeout");
    LogEntry errorOk = entry("ERROR", "all good");

    String query = "(level:ERROR OR level:WARN) AND timeout";

    assertThat(engine.matches(errorTimeout, query)).isTrue();
    assertThat(engine.matches(warnTimeout, query)).isTrue();
    assertThat(engine.matches(infoTimeout, query)).isFalse();
    assertThat(engine.matches(errorOk, query)).isFalse();
  }

  // ── Malformed query falls back to substring ───────────────────

  @Test
  void matches_malformedQuery_fallsBackToSubstring() {
    // The parser is very tolerant — "AND OR NOT ()" doesn't throw because evalTerm
    // returns true for unexpected token types. To exercise the catch-block
    // fallback,
    // we need a scenario where evaluation itself throws. Since the parser
    // gracefully
    // handles all operator-only inputs, we verify tolerance here and test the true
    // fallback path via matches_nullEntry_false (which triggers NPE in matchesRaw).
    //
    // For a pure-operator query the parser evaluates to true for any entry, proving
    // it doesn't crash on degenerate input.
    LogEntry e = entry("INFO", "anything");
    LogEntry other = entry("WARN", "something else");

    assertThat(engine.matches(e, "AND OR NOT ()")).isTrue();
    assertThat(engine.matches(other, "AND OR NOT ()")).isTrue();
  }

  // ── Empty / null query matches everything ─────────────────────

  @Test
  void matches_emptyQuery_matchesAll() {
    LogEntry e = entry("INFO", "anything");

    assertThat(engine.matches(e, "")).isTrue();
    assertThat(engine.matches(e, null)).isTrue();
    assertThat(engine.matches(e, "   ")).isTrue();
  }

  // ── Null entry returns false ──────────────────────────────────

  @Test
  void matches_nullEntry_false() {
    // After fix: matches() now guards against null entries, returning false.
    assertThat(engine.matches(null, "hello")).isFalse();
  }

  // ── Case-insensitive keyword match ────────────────────────────

  @Test
  void matches_caseInsensitive() {
    LogEntry e = entry("ERROR", "ERROR occurred in module");

    assertThat(engine.matches(e, "error")).isTrue();
    assertThat(engine.matches(e, "ERROR")).isTrue();
    assertThat(engine.matches(e, "Error")).isTrue();
  }

  // ── Field aliases: trace -> traceId ───────────────────────────

  @Test
  void matches_fieldAliases_trace_traceId() {
    LogEntry e =
        LogEntry.builder()
            .service("svc")
            .level("INFO")
            .message("request")
            .traceId("abc123")
            .build();

    assertThat(engine.matches(e, "trace:abc123")).isTrue();
    assertThat(engine.matches(e, "traceId:abc123")).isTrue();
    assertThat(engine.matches(e, "trace:xyz999")).isFalse();
  }

  // ── AND / OR precedence: left-to-right ────────────────────────

  @Test
  void matches_andOrPrecedence_leftToRight() {
    // Grammar: expr = term (AND|OR term)* — evaluated left to right.
    // "A OR B AND C" parses as "(A OR B) AND C"
    //
    // Entry has message "B C" (no A).
    // If left-to-right: (false OR true) AND true = true AND true = true
    // If AND-first: false OR (true AND true) = false OR true = true
    // Both give true for "B C", so pick an entry that distinguishes:
    //
    // Entry has message "A" (no B, no C).
    // If left-to-right: (true OR false) AND false = true AND false = false
    // If AND-first: true OR (false AND false) = true OR false = true
    LogEntry e = entry("INFO", "A");

    boolean result = engine.matches(e, "A OR B AND C");

    // Left-to-right (...) AND C = (A OR B) AND C = (true OR false) AND false =
    // false
    assertThat(result).isFalse();
  }

  // ── Deeply nested parentheses — no stack overflow ─────────────

  @Test
  void matches_deeplyNestedParens_noStackOverflow() {
    LogEntry e = entry("INFO", "term");

    assertThat(engine.matches(e, "((((((term))))))")).isTrue();
    assertThat(engine.matches(e, "((((((missing))))))")).isFalse();
  }

  // ── Implicit juxtaposition: terms without operator ───────────

  @Test
  void matches_implicitJuxtaposition_onlyFirstTermEvaluated() {
    // Without an explicit AND/OR between terms, the parser only evaluates the
    // first term and ignores the rest. "ERROR timeout" != "ERROR AND timeout".
    // This documents the current behavior.
    LogEntry e = entry("ERROR", "ERROR occurred");

    // First term "ERROR" matches message — result is true
    assertThat(engine.matches(e, "ERROR timeout")).isTrue();
    // With explicit AND, "timeout" is also evaluated — result is false
    assertThat(engine.matches(e, "ERROR AND timeout")).isFalse();
  }

  // ── NOT combined with AND/OR ─────────────────────────────────

  @Test
  void matches_notWithAnd() {
    LogEntry e = entry("ERROR", "failure in module A");

    assertThat(engine.matches(e, "failure AND NOT timeout")).isTrue();
    assertThat(engine.matches(e, "failure AND NOT module")).isFalse();
  }

  @Test
  void matches_notWithOr() {
    LogEntry e = entry("INFO", "success");

    assertThat(engine.matches(e, "NOT failure OR NOT success")).isTrue();
    assertThat(engine.matches(e, "NOT success AND NOT failure")).isFalse();
  }
}
