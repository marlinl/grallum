package com.grallum.application.dto;

import com.grallum.application.model.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

/** Validated external data contract; contains no persistence behavior. */
public record DockerCleanupPolicyRequest(
    @Min(1) @Max(10000) int keepLatest,
    @Min(1) @Max(36500) int olderThanDays,
    @NotNull @Size(max = 200) String protectedPattern,
    boolean enabled,
    @Min(0) long expectedVersion) {}
