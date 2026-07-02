package com.honeywell.loglens.config;

import com.honeywell.loglens.model.ServiceConfig;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "loglens")
public class LogLensConfig {

  /** Services to monitor — configured in application.yml */
  private List<ServiceConfig> services = new ArrayList<>();

  /** Lines to read from end of file for live tail */
  private int tailLines = 200;

  /**
   * Max lines to scan per file per search request. Prevents OOM on very large log files. 100,000
   * lines ≈ ~100MB typical log file — scanned in < 1 second.
   */
  private int maxScanLines = 100_000;

  /**
   * Hard ceiling for per-request maxScanLines override from the UI dropdown. Requests exceeding
   * this value are capped silently.
   */
  private int maxScanLinesLimit = 10_000_000;

  /**
   * Thread pool size for parallel log scanning. 0 = auto (resource-aware: 50-60% of CPU cores,
   * bounded by heap and service count). Set to a positive number to override.
   */
  private int scanPoolSize = 0;

  /** Show live resource metrics in the header bar. Default: false. */
  private boolean showMetrics = false;
}
