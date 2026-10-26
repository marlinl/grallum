package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** Validated per-repository retention settings and optimistic revision. */
public record CleanupPolicy(
    int keepLatest, int olderThanDays, String protectedPattern, boolean enabled, long version) {}
