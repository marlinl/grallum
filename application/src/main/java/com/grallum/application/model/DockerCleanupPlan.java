package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** Complete immutable cleanup preview; never a client deletion command. */
public record DockerCleanupPlan(
    Instant capturedAt,
    long policyVersion,
    List<String> candidateDigests,
    List<DockerRetentionDecision> decisions) {
  /** Copies caller collections; no I/O. */
  public DockerCleanupPlan {
    candidateDigests = List.copyOf(candidateDigests);
    decisions = List.copyOf(decisions);
  }
}
