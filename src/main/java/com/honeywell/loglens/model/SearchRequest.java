package com.honeywell.loglens.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class SearchRequest {
  private List<String> services;

  private String level;

  // Multiple values supported — any match passes (OR logic within field)
  private List<String> traceIds; // replaces single traceId
  private List<String> spanIds; // replaces single spanId

  // Keep singular aliases for REST API convenience
  private String traceId;
  private String spanId;

  private String userId;
  private String siteId;
  private String tenantId;
  private String message;
  private String logger;
  private String query;

  // Time range from browser — sent as ISO local datetime strings
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm")
  private LocalDateTime from;

  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm")
  private LocalDateTime to;

  /**
   * Browser timezone offset in minutes (e.g. +330 for IST, -300 for EST). The log file timestamps
   * are in the server's local time. We use this offset to convert "from/to" from browser time → log
   * file time.
   */
  private Integer browserOffsetMinutes;

  private int limit = 500;
  private String sortOrder = "desc";

  /**
   * Legacy pagination cursor. Superseded by searchId-based cache pagination. Kept for backward
   * compatibility — used as fallback when cache expires and nextOffsets from the previous response
   * provide per-service resume positions.
   */
  private Long fileOffset;

  /**
   * Per-service pagination cursors for "load more". Each key is a service name, each value is the
   * byte offset to resume scanning from for that service's log file. When present, takes precedence
   * over fileOffset. Returned as nextOffsets in SearchResult.
   */
  private Map<String, Long> serviceOffsets;

  /**
   * Cache-based pagination. On first search: leave null. The response returns a searchId. On "Load
   * More": send the searchId back — the server serves the next page from its in-memory cache (zero
   * disk I/O). Falls back to offset-based scanning if cache expired.
   */
  private String searchId;

  /**
   * Per-request scan depth override from the UI dropdown. Null = use server default (maxScanLines).
   * Capped by server's maxScanLinesLimit.
   */
  private Integer maxScanLines;
}
