package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** Secret-free business audit fields. */
public record AuditEvent(
    Instant occurredAt, String actor, String action, String target, String outcome) {}
