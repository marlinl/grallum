package com.grallum.application.entity;

import com.grallum.application.model.*;
import java.time.*;
import java.util.*;

/** Persistence representation; never an HTTP response. */
public record AuditEventEntity(
    Instant occurredAt, String actor, String action, String target, String outcome) {}
