package com.grallum.application.entity;

import com.grallum.application.model.*;
import java.time.*;
import java.util.*;

/** Persistence representation; never an HTTP response. */
public record UserRefreshFamilyEntity(
    UUID id, UUID userId, long credentialVersion, Instant absoluteExpiresAt, Instant revokedAt) {}
