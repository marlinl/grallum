package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** Encoded token returned only through explicit credential responses. */
public record AccessToken(String value, Instant issuedAt, Instant expiresAt) {
  /** Returns a redacted description; pure. */
  @Override
  public String toString() {
    return "AccessToken[REDACTED]";
  }
}
