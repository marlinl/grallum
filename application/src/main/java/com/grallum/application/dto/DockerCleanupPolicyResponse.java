package com.grallum.application.dto;

import com.grallum.application.model.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

/** Validated external data contract; contains no persistence behavior. */
public record DockerCleanupPolicyResponse(
    int keepLatest, int olderThanDays, String protectedPattern, boolean enabled, long version) {}
