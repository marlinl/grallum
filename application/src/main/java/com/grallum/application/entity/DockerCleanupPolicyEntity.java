package com.grallum.application.entity;

import com.grallum.application.model.*;
import java.time.*;
import java.util.*;

/** Persistence representation; never an HTTP response. */
public record DockerCleanupPolicyEntity(
    long repositoryId,
    int keepLatest,
    int olderThanDays,
    String protectedPattern,
    boolean enabled,
    long version) {}
