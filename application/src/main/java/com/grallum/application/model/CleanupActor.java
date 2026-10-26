package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** Trusted user or internal scheduler identity for audit. */
public record CleanupActor(String name, Optional<UUID> userId) {}
