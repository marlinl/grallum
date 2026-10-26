package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** Cryptographically verified or internally generated JWT claims. */
public record JwtClaims(
    UUID subject,
    TokenAudience audience,
    long credentialVersion,
    UUID tokenId,
    Optional<UUID> familyId,
    Instant issuedAt,
    Instant notBefore,
    Instant expiresAt,
    List<RegistryGrant> grants) {
  /** Copies caller collections; no I/O. */
  public JwtClaims {
    grants = List.copyOf(grants);
  }
}
