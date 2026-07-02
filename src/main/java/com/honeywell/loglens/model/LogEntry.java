package com.honeywell.loglens.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LogEntry {
  private String service;

  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
  private LocalDateTime timestamp;

  private String level;
  private String userId;
  private String siteId;
  private String tenantId;
  private String traceId;
  private String spanId;
  private String logger;
  private String thread;

  /** First-line message only (no stack trace) */
  private String message;

  /** Stack trace / continuation lines — may be null */
  private String stackTrace;

  /** Full raw block (message + stackTrace). Never truncated. */
  private String rawLine;

  private int lineNumber;

  /**
   * Byte offset in the file where this entry was found. Used for "load more" pagination — next
   * request sends this offset to resume backward scanning from exactly this position. Not
   * serialized to JSON responses (internal use only).
   */
  @com.fasterxml.jackson.annotation.JsonIgnore private Long fileOffset;

  public boolean hasStackTrace() {
    return stackTrace != null && !stackTrace.isBlank();
  }
}
