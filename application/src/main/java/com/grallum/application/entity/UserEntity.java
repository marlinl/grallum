package com.grallum.application.entity;

import com.grallum.application.model.*;
import java.time.*;
import java.util.*;

/** Persistence representation; never an HTTP response. */
public record UserEntity(
    UUID id,
    String username,
    String passwordHash,
    boolean enabled,
    boolean administrator,
    long credentialVersion) {
  /** Redacts persistence values from diagnostics; pure. */
  @Override
  public String toString() {
    return "UserEntity[REDACTED]";
  }
}
