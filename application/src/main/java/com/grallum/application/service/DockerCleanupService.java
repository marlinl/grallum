package com.grallum.application.service;

import com.grallum.application.config.*;
import com.grallum.application.dto.*;
import com.grallum.application.entity.*;
import com.grallum.application.exception.*;
import com.grallum.application.model.*;
import com.grallum.application.repository.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Plans conservative retention and coordinates resumable evidence for local manifest deletion. */
@Service
public class DockerCleanupService {
  private final UserAuthorizationService authorization;
  private final DockerCleanupPolicyRepository policies;
  private final DockerCleanupRunRepository runs;
  private final DockerRepository repositories;
  private final DockerProperties docker;
  private final CleanupProperties properties;
  private final DockerCleanupProgressService progress;
  private final DockerStorageService storage;
  private final DockerRepositoryLockService locks;
  private final AuditService audit;
  private final Clock clock;

  /**
   * Retains cleanup collaborators; external I/O is never performed within a long database
   * transaction.
   */
  public DockerCleanupService(
      UserAuthorizationService authorization,
      DockerCleanupPolicyRepository policies,
      DockerCleanupRunRepository runs,
      DockerRepository repositories,
      DockerProperties docker,
      CleanupProperties properties,
      DockerCleanupProgressService progress,
      DockerStorageService storage,
      DockerRepositoryLockService locks,
      AuditService audit,
      Clock clock) {
    this.authorization = authorization;
    this.policies = policies;
    this.runs = runs;
    this.repositories = repositories;
    this.docker = docker;
    this.properties = properties;
    this.progress = progress;
    this.storage = storage;
    this.locks = locks;
    this.audit = audit;
    this.clock = clock;
  }

  /** Returns the current policy or disabled defaults after a current READ authorization check. */
  public DockerCleanupPolicyResponse policy(AuthenticatedUser user, long repositoryId) {
    authorization.requireRepository(user, repositoryId, RepositoryPermission.READ);
    return response(readPolicy(repositoryId));
  }

  /** Atomically saves a validated policy version and audit event; stale versions conflict. */
  @Transactional
  public DockerCleanupPolicyResponse savePolicy(
      AuthenticatedUser user, long repositoryId, DockerCleanupPolicyRequest request) {
    authorization.requireRepository(user, repositoryId, RepositoryPermission.MANAGE);
    var policy =
        new CleanupPolicy(
            request.keepLatest(),
            request.olderThanDays(),
            request.protectedPattern(),
            request.enabled(),
            request.expectedVersion());
    validate(policy);
    var entity =
        new DockerCleanupPolicyEntity(
            repositoryId,
            policy.keepLatest(),
            policy.olderThanDays(),
            policy.protectedPattern(),
            policy.enabled(),
            policy.version());
    if (!policies.save(entity, request.expectedVersion()))
      throw new BusinessException(
          BusinessErrorCode.CONFLICT, "Cleanup policy changed; reload before saving");
    audit.record(
        new AuditEvent(
            clock.instant(),
            user.username(),
            "UPDATE_CLEANUP_POLICY",
            Long.toString(repositoryId),
            "SUCCESS"));
    return response(readPolicy(repositoryId));
  }

  /** Captures a complete snapshot and records a preview without deleting manifests. */
  public DockerCleanupResult preview(AuthenticatedUser user, long repositoryId) {
    return manual(user, repositoryId, true);
  }

  /**
   * Rechecks enabled policy and current metadata under coordination before deleting any candidate.
   */
  public DockerCleanupResult run(AuthenticatedUser user, long repositoryId) {
    return manual(user, repositoryId, false);
  }

  /** Returns a task only after confirming current READ permission and repository ownership. */
  public DockerCleanupResult result(AuthenticatedUser user, long repositoryId, UUID runId) {
    authorization.requireRepository(user, repositoryId, RepositoryPermission.READ);
    var run =
        runs.findById(runId)
            .filter(value -> value.repositoryId() == repositoryId)
            .orElseThrow(
                () -> new BusinessException(BusinessErrorCode.NOT_FOUND, "Cleanup run not found"));
    return result(run);
  }

  /**
   * Enumerates enabled repositories in bounded pages; busy repositories receive explicit skipped
   * tasks.
   */
  public void runScheduled() {
    long after = 0;
    while (true) {
      var ids = policies.findEnabledRepositoryIds(after, 100);
      if (ids.isEmpty()) return;
      for (long id : ids) {
        after = id;
        try {
          var repo = systemRepository(id);
          var lease = locks.tryAcquire(id);
          if (lease.isEmpty()) {
            var task =
                progress.create(
                    repo, new CleanupActor("scheduler", Optional.empty()), false, readPolicy(id));
            progress.finish(task, CleanupRunStatus.SKIPPED, "Repository busy");
            continue;
          }
          try (var held = lease.get()) {
            if (readPolicy(id).enabled())
              execute(repo, new CleanupActor("scheduler", Optional.empty()), false);
          }
        } catch (RuntimeException e) {
          System.getLogger(getClass().getName())
              .log(System.Logger.Level.WARNING, "Scheduled cleanup failed for repository " + id);
        }
      }
    }
  }

  /**
   * Reconciles unfinished intent records without issuing new DELETE requests or resuming untouched
   * candidates.
   */
  public void recoverIncompleteRuns() {
    for (var run : runs.findUnfinished(properties.recoveryBatch())) {
      var lease = locks.tryAcquire(run.repositoryId());
      if (lease.isEmpty()) continue;
      try (var held = lease.get()) {
        var current = runs.findById(run.id()).orElseThrow();
        if (current.status() != CleanupRunStatus.PENDING
            && current.status() != CleanupRunStatus.RUNNING
            && runs.findItems(run.id()).stream()
                .noneMatch(item -> item.status() == CleanupItemStatus.INTENT)) continue;
        var repo = systemRepository(run.repositoryId());
        for (var item : runs.findItems(run.id()))
          if (item.status() == CleanupItemStatus.INTENT
              || item.status() == CleanupItemStatus.UNCERTAIN) {
            try {
              storage.readManifest(repo, item.digest());
              runs.updateItem(run.id(), item.digest(), CleanupItemStatus.UNCERTAIN);
            } catch (BusinessException e) {
              if (e.code() == BusinessErrorCode.NOT_FOUND)
                progress.confirm(
                    run.id(), repo.id(), item.digest(), DockerDeleteResult.ALREADY_ABSENT);
              else throw e;
            }
          }
        var items = runs.findItems(run.id());
        boolean confirmed = items.stream().anyMatch(DockerCleanupService::confirmed);
        progress.finish(
            run.id(),
            confirmed ? CleanupRunStatus.PARTIAL : CleanupRunStatus.FAILED,
            "Interrupted execution reconciled; untouched candidates were not deleted");
      } catch (RuntimeException e) {
        System.getLogger(getClass().getName())
            .log(System.Logger.Level.WARNING, "Cleanup recovery remains pending for " + run.id());
      }
    }
  }

  /** Computes retention reasons and digest candidates solely from a complete supplied snapshot. */
  DockerCleanupPlan plan(
      List<DockerTag> tags, CleanupPolicy policy, Instant now, Set<String> dependencies) {
    validate(policy);
    var sorted =
        tags.stream()
            .sorted(
                Comparator.comparing(DockerTag::firstSeen).reversed().thenComparing(DockerTag::tag))
            .toList();
    Map<String, List<String>> names = new LinkedHashMap<>();
    Map<String, Set<RetentionReason>> reasons = new LinkedHashMap<>();
    Set<String> individuallyRetained = new HashSet<>();
    for (int i = 0; i < sorted.size(); i++) {
      var tag = sorted.get(i);
      names.computeIfAbsent(tag.digest(), key -> new ArrayList<>()).add(tag.tag());
      var keep =
          reasons.computeIfAbsent(tag.digest(), key -> EnumSet.noneOf(RetentionReason.class));
      boolean retained = false;
      if (i < policy.keepLatest()) {
        keep.add(RetentionReason.RECENT_COUNT);
        retained = true;
      }
      if (!tag.firstSeen().isBefore(now.minus(Duration.ofDays(policy.olderThanDays())))) {
        keep.add(RetentionReason.AGE);
        retained = true;
      }
      if (protectedTag(tag.tag(), policy.protectedPattern())) {
        keep.add(RetentionReason.PATTERN);
        retained = true;
      }
      if (tag.index() || dependencies.contains(tag.digest())) {
        keep.add(RetentionReason.INDEX_DEPENDENCY);
        retained = true;
      }
      if (retained) individuallyRetained.add(tag.tag());
    }
    List<DockerRetentionDecision> decisions = new ArrayList<>();
    List<String> candidates = new ArrayList<>();
    for (var entry : names.entrySet()) {
      var keep = reasons.get(entry.getKey());
      if (!keep.isEmpty()
          && entry.getValue().stream().anyMatch(tag -> !individuallyRetained.contains(tag)))
        keep.add(RetentionReason.SHARED_DIGEST);
      boolean candidate = keep.isEmpty();
      if (candidate) candidates.add(entry.getKey());
      decisions.add(new DockerRetentionDecision(entry.getKey(), entry.getValue(), keep, candidate));
    }
    return new DockerCleanupPlan(now, policy.version(), candidates, decisions);
  }

  /**
   * Acquires an interruptible repository lease and rechecks current permissions before manual work.
   */
  private DockerCleanupResult manual(AuthenticatedUser user, long repositoryId, boolean dryRun) {
    var repo = authorization.requireRepository(user, repositoryId, RepositoryPermission.MANAGE);
    try (var lease = locks.acquire(repositoryId, Duration.ofSeconds(30))) {
      authorization.requireRepository(user, repositoryId, RepositoryPermission.MANAGE);
      return execute(repo, new CleanupActor(user.username(), Optional.of(user.id())), dryRun);
    }
  }

  /**
   * Runs one complete snapshot and durable intent sequence while its caller owns repository
   * coordination.
   */
  private DockerCleanupResult execute(
      DockerRepositoryRef repo, CleanupActor actor, boolean dryRun) {
    var policy = readPolicy(repo.id());
    if (!dryRun && !policy.enabled())
      throw new BusinessException(BusinessErrorCode.CONFLICT, "Enable cleanup before execution");
    var runId = progress.create(repo, actor, dryRun, policy);
    boolean deleted = false;
    try {
      List<DockerTaggedManifest> complete = new ArrayList<>();
      Set<String> dependencies = new HashSet<>();
      for (String tag : storage.listAllTags(repo)) {
        var manifest = storage.readManifest(repo, tag);
        complete.add(new DockerTaggedManifest(tag, manifest));
        if (manifest.index()) collect(repo, manifest, dependencies, new HashSet<>(), 0);
      }
      var now = clock.instant();
      var tags = storage.synchronizeObservations(repo, complete, now);
      var plan = plan(tags, policy, now, dependencies);
      progress.start(runId, plan);
      if (!dryRun)
        for (String digest : plan.candidateDigests()) {
          progress.intent(runId, digest);
          var outcome = storage.deleteManifest(repo, digest);
          deleted = true;
          progress.confirm(runId, repo.id(), digest, outcome);
        }
      progress.finish(runId, CleanupRunStatus.SUCCEEDED, "");
    } catch (RuntimeException e) {
      progress.finish(
          runId,
          deleted || runs.findItems(runId).stream().anyMatch(DockerCleanupService::confirmed)
              ? CleanupRunStatus.PARTIAL
              : CleanupRunStatus.FAILED,
          "Cleanup stopped; inspect persisted item states before retrying");
    }
    return result(runs.findById(runId).orElseThrow());
  }

  /**
   * Protects index graphs with bounded depth and size, rejecting cycles or unavailable nested
   * indexes.
   */
  private void collect(
      DockerRepositoryRef repo,
      DockerManifest manifest,
      Set<String> dependencies,
      Set<String> path,
      int depth) {
    if (depth > properties.maximumIndexDepth() || !path.add(manifest.digest()))
      throw new BusinessException(BusinessErrorCode.CONFLICT, "Unsupported manifest index graph");
    dependencies.add(manifest.digest());
    if (dependencies.size() > 100000)
      throw new BusinessException(
          BusinessErrorCode.CONFLICT, "Manifest graph exceeds cleanup limit");
    for (var reference : manifest.references()) {
      if (path.contains(reference.digest()))
        throw new BusinessException(BusinessErrorCode.CONFLICT, "Cyclic manifest index");
      boolean fresh = dependencies.add(reference.digest());
      if (dependencies.size() > 100000)
        throw new BusinessException(
            BusinessErrorCode.CONFLICT, "Manifest graph exceeds cleanup limit");
      if (fresh
          && (reference.mediaType().equals("application/vnd.oci.image.index.v1+json")
              || reference
                  .mediaType()
                  .equals("application/vnd.docker.distribution.manifest.list.v2+json")))
        collect(
            repo, storage.readManifest(repo, reference.digest()), dependencies, path, depth + 1);
    }
    path.remove(manifest.digest());
  }

  /**
   * Reads the current policy or conservative disabled defaults without performing authorization.
   */
  private CleanupPolicy readPolicy(long id) {
    return policies
        .findByRepositoryId(id)
        .map(
            p ->
                new CleanupPolicy(
                    p.keepLatest(),
                    p.olderThanDays(),
                    p.protectedPattern(),
                    p.enabled(),
                    p.version()))
        .orElse(new CleanupPolicy(10, 30, "latest,stable,release-*", false, 0));
  }

  /** Resolves a configured registered repository for an explicitly named system cleanup actor. */
  private DockerRepositoryRef systemRepository(long id) {
    var row = repositories.findById(id).orElseThrow();
    var config = docker.registries().get(row.registry());
    if (config == null)
      throw new BusinessException(BusinessErrorCode.UNAVAILABLE, "Registry unavailable");
    return new DockerRepositoryRef(
        row.id(), new DockerRepositoryName(row.registry(), row.name()), config.mode());
  }

  /**
   * Projects durable task and item states without claiming ambiguous external deletions succeeded.
   */
  private DockerCleanupResult result(DockerCleanupRunEntity run) {
    var items = runs.findItems(run.id());
    List<CleanupFailure> failures = new ArrayList<>();
    for (var item : items)
      if (item.status() == CleanupItemStatus.INTENT || item.status() == CleanupItemStatus.UNCERTAIN)
        failures.add(
            new CleanupFailure(item.digest(), "UNCERTAIN", "Deletion requires reconciliation"));
    if (!run.safeError().isBlank())
      failures.add(new CleanupFailure("", "RUN_STOPPED", run.safeError()));
    return new DockerCleanupResult(
        run.id(),
        run.dryRun(),
        run.status(),
        items.stream().map(DockerCleanupItemEntity::digest).toList(),
        items.stream()
            .filter(DockerCleanupService::confirmed)
            .map(DockerCleanupItemEntity::digest)
            .toList(),
        failures);
  }

  /** Tests whether an item has durable confirmation of deletion or prior absence. */
  private static boolean confirmed(DockerCleanupItemEntity item) {
    return item.status() == CleanupItemStatus.DELETED
        || item.status() == CleanupItemStatus.ALREADY_ABSENT;
  }

  /** Projects a domain policy into its versioned caller-visible response. */
  private static DockerCleanupPolicyResponse response(CleanupPolicy p) {
    return new DockerCleanupPolicyResponse(
        p.keepLatest(), p.olderThanDays(), p.protectedPattern(), p.enabled(), p.version());
  }

  /** Enforces bounded retention and a simple comma-separated asterisk-only glob grammar. */
  private static void validate(CleanupPolicy p) {
    if (p.keepLatest() < 1
        || p.keepLatest() > 10000
        || p.olderThanDays() < 1
        || p.olderThanDays() > 36500
        || p.version() < 0
        || p.protectedPattern() == null
        || p.protectedPattern().length() > 200
        || !p.protectedPattern().matches("[A-Za-z0-9_.* ,\\-]*"))
      throw new BusinessException(BusinessErrorCode.INVALID_INPUT, "Invalid cleanup policy");
  }

  /** Matches literal tag fragments with only the documented asterisk wildcard. */
  private static boolean protectedTag(String tag, String patterns) {
    return Arrays.stream(patterns.split(","))
        .map(String::trim)
        .filter(p -> !p.isEmpty())
        .anyMatch(
            p ->
                tag.matches(
                    Arrays.stream(p.split("\\*", -1))
                        .map(Pattern::quote)
                        .reduce((a, b) -> a + ".*" + b)
                        .orElse("")));
  }
}
