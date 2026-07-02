package com.honeywell.loglens;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for LogLens — lightweight log viewer for WES microservices.
 *
 * @author Mandip Pandit (H504024)
 */
@SpringBootApplication
@EnableConfigurationProperties
@EnableScheduling
public class LogLensApplication {
  public static void main(String[] args) {
    SpringApplication.run(LogLensApplication.class, args);
  }
}
