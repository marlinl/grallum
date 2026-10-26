package com.grallum.application.config;

import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Trusted public origin, bootstrap and secure-cookie deployment configuration. */
@ConfigurationProperties("app")
public record WebProperties(URI publicBaseUrl, Security security, Bootstrap bootstrap) {
  /** Requires a trusted origin and an explicit loopback-only exception for HTTP cookies. */
  public WebProperties {
    security = security == null ? new Security(new Cookies(true)) : security;
    if (publicBaseUrl == null
        || publicBaseUrl.getHost() == null
        || publicBaseUrl.getUserInfo() != null
        || publicBaseUrl.getQuery() != null
        || publicBaseUrl.getFragment() != null
        || !(publicBaseUrl.getPath().isEmpty() || publicBaseUrl.getPath().equals("/")))
      throw new IllegalArgumentException("Configure a public origin");
    if (security.cookies().secure()) {
      if (!publicBaseUrl.getScheme().equals("https"))
        throw new IllegalArgumentException("Secure cookies require HTTPS public origin");
    } else if (!publicBaseUrl.getScheme().equals("http")
        || !Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(publicBaseUrl.getHost())) {
      throw new IllegalArgumentException(
          "Insecure development cookies require a loopback HTTP origin");
    }
    if (publicBaseUrl.getPath().equals("/"))
      publicBaseUrl =
          URI.create(publicBaseUrl.toString().substring(0, publicBaseUrl.toString().length() - 1));
    bootstrap = bootstrap == null ? new Bootstrap("admin", "") : bootstrap;
  }

  /** Security configuration subset; JWT settings bind independently. */
  public record Security(Cookies cookies) {
    /** Defaults unspecified cookie policy to Secure. */
    public Security {
      cookies = cookies == null ? new Cookies(true) : cookies;
    }
  }

  /** Secure-cookie deployment switch; false is allowed only on loopback HTTP. */
  public record Cookies(boolean secure) {}

  /** First-start administrator credentials; never printed. */
  public record Bootstrap(String username, String password) {
    /** Redacts the bootstrap secret; pure. */
    @Override
    public String toString() {
      return "Bootstrap[REDACTED]";
    }
  }
}
