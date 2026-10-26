package com.grallum.application.service;

import com.grallum.application.config.*;
import com.grallum.application.dto.*;
import com.grallum.application.entity.*;
import com.grallum.application.exception.*;
import com.grallum.application.model.*;
import com.grallum.application.repository.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Commits cleanup intent and confirmed results in short transactions around external deletion. */
@Service
public class DockerCleanupProgressService {
  private final DockerCleanupRunRepository runs;
  private final DockerTagObservationRepository observations;
  private final AuditService audit;
  private final Clock clock;
  private final JsonMapper json;

  /** Retains progress collaborators without starting transactions or external operations. */
  public DockerCleanupProgressService(
      DockerCleanupRunRepository runs,
      DockerTagObservationRepository observations,
      AuditService audit,
      Clock clock,
      JsonMapper json) {
    this.runs = runs;
    this.observations = observations;
    this.audit = audit;
    this.clock = clock;
    this.json = json;
  }

  /** Creates a durable pending task with its immutable policy snapshot and actor. */
  @Transactional
  public UUID create(
      DockerRepositoryRef repository, CleanupActor actor, boolean dryRun, CleanupPolicy policy) {
    var id = UUID.randomUUID();
    runs.insert(
        new DockerCleanupRunEntity(
            id,
            repository.id(),
            actor.name(),
            dryRun,
            policy.version(),
            json.writeValueAsString(policy),
            CleanupRunStatus.PENDING,
            clock.instant(),
            null,
            ""));
    return id;
  }

  /** Saves the complete candidate set and starts the task in one committed transaction. */
  @Transactional
  public void start(UUID runId, DockerCleanupPlan plan) {
    runs.insertCandidates(runId, plan.candidateDigests());
    runs.updateStatus(runId, CleanupRunStatus.RUNNING, null, "");
  }

  /** Commits a deletion intent before any external DELETE is attempted. */
  @Transactional
  public void intent(UUID runId, String digest) {
    runs.updateItem(runId, digest, CleanupItemStatus.INTENT);
    var run = runs.findById(runId).orElseThrow();
    audit.record(
        new AuditEvent(clock.instant(), run.actor(), "CLEANUP_INTENT", runId.toString(), digest));
  }

  /**
   * Atomically confirms deletion or absence, removes matching observations and records the audit
   * result.
   */
  @Transactional
  public void confirm(UUID runId, long repositoryId, String digest, DockerDeleteResult result) {
    runs.updateItem(
        runId,
        digest,
        result == DockerDeleteResult.DELETED
            ? CleanupItemStatus.DELETED
            : CleanupItemStatus.ALREADY_ABSENT);
    observations.deleteByDigest(repositoryId, digest);
    var run = runs.findById(runId).orElseThrow();
    audit.record(
        new AuditEvent(
            clock.instant(),
            run.actor(),
            "CLEANUP_CONFIRMED",
            runId.toString(),
            result.name() + ":" + digest));
  }

  /** Commits a terminal status without rolling back earlier confirmed external effects. */
  @Transactional
  public void finish(UUID runId, CleanupRunStatus status, String safeError) {
    runs.updateStatus(runId, status, clock.instant(), safeError);
    var run = runs.findById(runId).orElseThrow();
    audit.record(
        new AuditEvent(
            clock.instant(), run.actor(), "CLEANUP_FINISHED", runId.toString(), status.name()));
  }
}
