package com.grallum.application.config;

import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit trusted RSA key locations and bounded JWT lifetimes. */
@ConfigurationProperties("app.security.jwt")
public record JwtProperties(
    String issuer,
    String activeKeyId,
    Path privateKeyLocation,
    Map<String, Path> verificationKeys,
    Duration managementTtl,
    Duration registryTtl,
    Duration clockSkew) {
  /**
   * Validates required key configuration and token limits; never generates fallback production
   * keys.
   */
  public JwtProperties {
    if (issuer == null
        || issuer.isBlank()
        || activeKeyId == null
        || activeKeyId.isBlank()
        || privateKeyLocation == null
        || verificationKeys == null
        || !verificationKeys.containsKey(activeKeyId))
      throw new IllegalArgumentException("Configure JWT issuer and RSA keys");
    verificationKeys = Map.copyOf(verificationKeys);
    managementTtl = managementTtl == null ? Duration.ofMinutes(10) : managementTtl;
    registryTtl = registryTtl == null ? Duration.ofMinutes(5) : registryTtl;
    clockSkew = clockSkew == null ? Duration.ofSeconds(30) : clockSkew;
    if (managementTtl.isNegative()
        || managementTtl.isZero()
        || managementTtl.compareTo(Duration.ofHours(1)) > 0
        || registryTtl.isNegative()
        || registryTtl.isZero()
        || registryTtl.compareTo(Duration.ofMinutes(15)) > 0
        || clockSkew.isNegative()
        || clockSkew.compareTo(Duration.ofSeconds(30)) > 0)
      throw new IllegalArgumentException("Invalid JWT lifetime");
  }
}
