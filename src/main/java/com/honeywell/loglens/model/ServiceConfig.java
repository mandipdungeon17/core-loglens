package com.honeywell.loglens.model;

import lombok.Data;

@Data
public class ServiceConfig {
  private String name; // e.g. "routing"
  private String logFile; // e.g. /honeywell/logs/routing.log
  private String color; // hex e.g. #1a6fa3
}
