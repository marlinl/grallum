package com.grallum.application.config;

import com.grallum.application.model.DockerRegistryMode;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Validated trusted Docker endpoints and bounded transfer settings. */
@ConfigurationProperties("app")
public record DockerProperties(Map<String, Registry> registries, RegistrySettings registry) {
  /** Copies validated aliases; startup fails for missing or unsafe endpoint configuration. */
  public DockerProperties {
    if (registries == null || registries.isEmpty())
      throw new IllegalArgumentException("Configure app.registries");
    registries.forEach(
        (alias, value) -> {
          if (!alias.matches("[a-z0-9-]{1,64}") || value == null)
            throw new IllegalArgumentException("Invalid registry alias");
        });
    registries = Map.copyOf(registries);
    registry = registry == null ? new RegistrySettings(null, null, null, 0) : registry;
  }

  /** Trusted backend coordinates; not accepted from user HTTP requests. */
  public record Registry(URI url, DockerRegistryMode mode, URI managementUrl) {
    /** Requires origin-only HTTP(S) endpoints and an explicit mode; no network I/O. */
    public Registry {
      endpoint(url);
      if (mode == null) throw new IllegalArgumentException("Registry mode is required");
      if (managementUrl != null) endpoint(managementUrl);
      if (mode == DockerRegistryMode.PROXY && managementUrl == null)
        throw new IllegalArgumentException("Proxy management URL is required");
    }

    /**
     * Rejects credentials, queries, fragments and path prefixes in trusted endpoint configuration.
     */
    private static void endpoint(URI value) {
      if (value == null
          || !Set.of("http", "https").contains(value.getScheme())
          || value.getHost() == null
          || value.getUserInfo() != null
          || value.getQuery() != null
          || value.getFragment() != null
          || !(value.getPath().isEmpty() || value.getPath().equals("/")))
        throw new IllegalArgumentException("Registry endpoint must be an HTTP(S) origin");
    }
  }

  /** Deployment-level bounds; metadata size is enforced while reading. */
  public record RegistrySettings(
      Duration connectTimeout,
      Duration metadataTimeout,
      Duration transferTimeout,
      int metadataMaxBytes) {
    /** Supplies documented defaults and rejects nonpositive or excessive bounds. */
    public RegistrySettings {
      connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
      metadataTimeout = metadataTimeout == null ? Duration.ofSeconds(60) : metadataTimeout;
      transferTimeout = transferTimeout == null ? Duration.ofMinutes(30) : transferTimeout;
      metadataMaxBytes = metadataMaxBytes == 0 ? 8 * 1024 * 1024 : metadataMaxBytes;
      if (connectTimeout.isNegative()
          || connectTimeout.isZero()
          || metadataTimeout.isNegative()
          || metadataTimeout.isZero()
          || transferTimeout.isNegative()
          || transferTimeout.isZero()
          || metadataMaxBytes < 1024
          || metadataMaxBytes > 64 * 1024 * 1024)
        throw new IllegalArgumentException("Invalid Docker transfer limits");
    }
  }
}
