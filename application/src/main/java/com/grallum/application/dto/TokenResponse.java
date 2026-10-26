package com.grallum.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.grallum.application.model.AccessToken;
import java.time.*;
import java.util.*;

/** Explicit credential exchange response; never render it in ordinary pages or logs. */
public record TokenResponse(
    String token,
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("expires_in") long expiresIn,
    @JsonProperty("issued_at") Instant issuedAt) {
  /** Converts an issued access token to the protocol response; pure. */
  public static TokenResponse from(AccessToken token) {
    return new TokenResponse(
        token.value(),
        token.value(),
        Duration.between(token.issuedAt(), token.expiresAt()).toSeconds(),
        token.issuedAt());
  }

  /** Redacts encoded credentials from diagnostics; pure. */
  @Override
  public String toString() {
    return "TokenResponse[REDACTED]";
  }
}
