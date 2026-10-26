package com.grallum.application.config;

import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit scheduler zone and bounded cleanup recovery settings. */
@ConfigurationProperties("app.cleanup")
public record CleanupProperties(
    String cron,
    String zone,
    int recoveryBatch,
    int maximumIndexDepth,
    @org.springframework.boot.context.properties.bind.DefaultValue("true")
        boolean schedulingEnabled) {
  /** Validates time zone and operational bounds; no scheduling occurs during binding. */
  public CleanupProperties {
    cron = cron == null ? "0 0 3 * * *" : cron;
    if (zone == null || zone.isBlank())
      throw new IllegalArgumentException("Configure app.cleanup.zone");
    ZoneId.of(zone);
    recoveryBatch = recoveryBatch == 0 ? 100 : recoveryBatch;
    maximumIndexDepth = maximumIndexDepth == 0 ? 8 : maximumIndexDepth;
    if (recoveryBatch < 1
        || recoveryBatch > 1000
        || maximumIndexDepth < 1
        || maximumIndexDepth > 32) throw new IllegalArgumentException("Invalid cleanup limits");
  }
}
