package com.grallum.application.entity;

import com.grallum.application.model.*;
import java.time.*;
import java.util.*;

/** Persistence representation; never an HTTP response. */
public record DockerCleanupRunEntity(
    UUID id,
    long repositoryId,
    String actor,
    boolean dryRun,
    long policyVersion,
    String policySnapshot,
    CleanupRunStatus status,
    Instant startedAt,
    Instant finishedAt,
    String safeError) {}
