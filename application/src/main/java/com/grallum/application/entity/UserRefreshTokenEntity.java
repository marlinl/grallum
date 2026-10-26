package com.grallum.application.entity;

import com.grallum.application.model.*;
import java.time.*;
import java.util.*;

/** Persistence representation; never an HTTP response. */
public record UserRefreshTokenEntity(
    UUID id,
    UUID familyId,
    String secretHash,
    Instant expiresAt,
    Instant consumedAt,
    UUID replacementId) {
  /** Redacts persistence values from diagnostics; pure. */
  @Override
  public String toString() {
    return "UserRefreshTokenEntity[REDACTED]";
  }
}
