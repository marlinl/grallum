package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** Explainable immutable per-digest retention decision. */
public record DockerRetentionDecision(
    String digest, List<String> tags, Set<RetentionReason> reasons, boolean candidate) {
  /** Copies caller collections; no I/O. */
  public DockerRetentionDecision {
    tags = List.copyOf(tags);
    reasons = Set.copyOf(reasons);
  }
}
