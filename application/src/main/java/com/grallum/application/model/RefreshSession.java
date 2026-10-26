package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** Committed browser login family and fixed expiry. */
public record RefreshSession(
    UUID familyId, UUID userId, long credentialVersion, Instant absoluteExpiresAt) {}
