package com.honeywell.loglens.service;

import com.honeywell.loglens.model.LogEntry;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Parses and evaluates free-form log search queries with zero overhead — runs entirely in-memory,
 * no indexing, no network.
 *
 * <p>── Supported syntax ─────────────────────────────────────────────
 *
 * <p>Keyword (searches message + rawLine): containerId missing "containerId missing" (quoted phrase
 * — exact match)
 *
 * <p>Field:value: level:ERROR userId:U001 siteId:SITE-A tenantId:T1 traceId:abc123 spanId:xyz789
 * logger:DivertConfirmEventHandler service:routing message:"containerId missing"
 *
 * <p>Boolean operators (case-insensitive): level:ERROR AND userId:U001 traceId:abc123 OR
 * traceId:b7d1 level:ERROR NOT message:timeout NOT level:DEBUG
 *
 * <p>Combinations: level:ERROR AND siteId:SITE-A AND message:"containerId missing"
 * logger:DivertConfirmEventHandler AND level:ERROR (level:ERROR OR level:WARN) AND service:routing
 *
 * <p>── Examples from real WES debugging ──────────────────────────── level:ERROR AND userId:U001
 * AND siteId:SITE-A traceId:a3f9c2b1 AND NOT level:DEBUG message:"divert confirm" AND level:WARN
 * logger:DivertConfirmEventHandler level:ERROR OR level:WARN
 */
@Service
public class QueryEngine {

  // Token types
  private enum TType {
    AND,
    OR,
    NOT,
    LPAREN,
    RPAREN,
    TERM
  }

  private record Token(TType type, String value) {}

  // ── PUBLIC API ───────────────────────────────────────────────

  /**
   * Returns true if the entry matches the query expression. Returns true for null/blank queries (no
   * filter).
   */
  public boolean matches(LogEntry entry, String query) {
    if (query == null || query.isBlank()) return true;
    if (entry == null) return false;
    try {
      List<Token> tokens = tokenize(query.trim());
      if (tokens.isEmpty()) return true;
      int[] pos = {0};
      return evalExpr(tokens, pos, entry);
    } catch (Exception e) {
      // Malformed query — fall back to simple substring match
      return matchesRaw(entry, query);
    }
  }

  // ── TOKENIZER ────────────────────────────────────────────────

  private List<Token> tokenize(String input) {
    List<Token> tokens = new ArrayList<>();
    int i = 0;
    while (i < input.length()) {
      char c = input.charAt(i);

      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }

      if (c == '(') {
        tokens.add(new Token(TType.LPAREN, "("));
        i++;
        continue;
      }
      if (c == ')') {
        tokens.add(new Token(TType.RPAREN, ")"));
        i++;
        continue;
      }

      // Quoted string "foo bar"
      if (c == '"') {
        int end = input.indexOf('"', i + 1);
        if (end == -1) end = input.length();
        tokens.add(new Token(TType.TERM, input.substring(i + 1, end)));
        i = end + 1;
        continue;
      }

      // Read a word
      int start = i;
      while (i < input.length()
          && !Character.isWhitespace(input.charAt(i))
          && input.charAt(i) != '('
          && input.charAt(i) != ')') {
        i++;
      }
      String word = input.substring(start, i);

      switch (word.toUpperCase()) {
        case "AND" -> tokens.add(new Token(TType.AND, word));
        case "OR" -> tokens.add(new Token(TType.OR, word));
        case "NOT" -> tokens.add(new Token(TType.NOT, word));
        default -> tokens.add(new Token(TType.TERM, word));
      }
    }
    return tokens;
  }

  // ── RECURSIVE DESCENT PARSER ─────────────────────────────────
  // Grammar:
  // expr ::= term (AND|OR term)*
  // term ::= NOT term | '(' expr ')' | atom

  private boolean evalExpr(List<Token> tokens, int[] pos, LogEntry entry) {
    boolean left = evalTerm(tokens, pos, entry);

    while (pos[0] < tokens.size()) {
      Token t = tokens.get(pos[0]);
      if (t.type() == TType.AND) {
        pos[0]++;
        boolean right = evalTerm(tokens, pos, entry);
        left = left && right;
      } else if (t.type() == TType.OR) {
        pos[0]++;
        boolean right = evalTerm(tokens, pos, entry);
        left = left || right;
      } else {
        break;
      }
    }
    return left;
  }

  private boolean evalTerm(List<Token> tokens, int[] pos, LogEntry entry) {
    if (pos[0] >= tokens.size()) return true;
    Token t = tokens.get(pos[0]);

    if (t.type() == TType.NOT) {
      pos[0]++;
      return !evalTerm(tokens, pos, entry);
    }
    if (t.type() == TType.LPAREN) {
      pos[0]++; // consume '('
      boolean result = evalExpr(tokens, pos, entry);
      if (pos[0] < tokens.size() && tokens.get(pos[0]).type() == TType.RPAREN) {
        pos[0]++; // consume ')'
      }
      return result;
    }
    if (t.type() == TType.TERM) {
      pos[0]++;
      return evalAtom(t.value(), entry);
    }
    return true;
  }

  // ── ATOM EVALUATION ──────────────────────────────────────────

  private boolean evalAtom(String atom, LogEntry entry) {
    // field:value syntax
    int colon = atom.indexOf(':');
    if (colon > 0) {
      String field = atom.substring(0, colon).toLowerCase().trim();
      String value = atom.substring(colon + 1).trim();
      return matchField(field, value, entry);
    }
    // Plain keyword — search message and rawLine
    return matchesRaw(entry, atom);
  }

  private boolean matchField(String field, String value, LogEntry entry) {
    if (value.isBlank()) return true;
    String v = value.toLowerCase();

    return switch (field) {
      case "level" -> entry.getLevel() != null && entry.getLevel().equalsIgnoreCase(value);
      case "service" -> entry.getService() != null && entry.getService().toLowerCase().contains(v);
      case "userid", "user" ->
          entry.getUserId() != null && entry.getUserId().toLowerCase().contains(v);
      case "siteid", "site" ->
          entry.getSiteId() != null && entry.getSiteId().toLowerCase().contains(v);
      case "tenantid", "tenant" ->
          entry.getTenantId() != null && entry.getTenantId().toLowerCase().contains(v);
      case "traceid", "trace" ->
          entry.getTraceId() != null && entry.getTraceId().toLowerCase().contains(v);
      case "spanid", "span" ->
          entry.getSpanId() != null && entry.getSpanId().toLowerCase().contains(v);
      case "logger", "class" ->
          entry.getLogger() != null && entry.getLogger().toLowerCase().contains(v);
      case "thread" -> entry.getThread() != null && entry.getThread().toLowerCase().contains(v);
      case "message", "msg" ->
          entry.getMessage() != null && entry.getMessage().toLowerCase().contains(v);
      default -> matchesRaw(entry, value);
    };
  }

  /** Fallback: substring search across message + rawLine */
  private boolean matchesRaw(LogEntry entry, String keyword) {
    String kw = keyword.toLowerCase();
    if (entry.getMessage() != null && entry.getMessage().toLowerCase().contains(kw)) return true;
    if (entry.getRawLine() != null && entry.getRawLine().toLowerCase().contains(kw)) return true;
    return false;
  }
}
