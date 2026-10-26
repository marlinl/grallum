package com.grallum.application.config;

import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Absolute browser-family lifetime; rotation never extends it. */
@ConfigurationProperties("app.security.refresh")
public record RefreshProperties(Duration absoluteTtl) {
  /** Applies the seven-day default and rejects unbounded family lifetimes. */
  public RefreshProperties {
    absoluteTtl = absoluteTtl == null ? Duration.ofDays(7) : absoluteTtl;
    if (absoluteTtl.isNegative()
        || absoluteTtl.isZero()
        || absoluteTtl.compareTo(Duration.ofDays(30)) > 0)
      throw new IllegalArgumentException("Invalid refresh lifetime");
  }
}
