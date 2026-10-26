package com.grallum.application.dto;

import com.grallum.application.model.*;
import java.time.*;
import java.util.*;

/** Durable cleanup outcome; callers must inspect status, not HTTP success alone. */
public record DockerCleanupResult(
    UUID runId,
    boolean dryRun,
    CleanupRunStatus status,
    List<String> candidateDigests,
    List<String> deletedDigests,
    List<CleanupFailure> failures) {
  /** Copies result collections; no I/O. */
  public DockerCleanupResult {
    candidateDigests = List.copyOf(candidateDigests);
    deletedDigests = List.copyOf(deletedDigests);
    failures = List.copyOf(failures);
  }
}
