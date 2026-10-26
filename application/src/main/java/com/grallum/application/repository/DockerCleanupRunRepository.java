package com.grallum.application.repository;

import com.grallum.application.entity.*;
import com.grallum.application.model.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Parameterized persistence for DockerCleanupRunRepository; no HTTP or authorization decisions. */
@Repository
public class DockerCleanupRunRepository {
  private final JdbcClient db;

  /** Retains the JDBC executor; construction performs no queries. */
  public DockerCleanupRunRepository(JdbcClient db) {
    this.db = db;
  }

  /** Maps an optional timestamp; pure apart from reading the current JDBC row. */
  private static Instant instant(ResultSet r, String column) throws SQLException {
    var t = r.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  /** Maps one task entity without exposing transport internals. */
  private static DockerCleanupRunEntity run(ResultSet r, int n) throws SQLException {
    return new DockerCleanupRunEntity(
        r.getObject("id", UUID.class),
        r.getLong("repository_id"),
        r.getString("actor"),
        r.getBoolean("dry_run"),
        r.getLong("policy_version"),
        r.getString("policy_snapshot"),
        CleanupRunStatus.valueOf(r.getString("status")),
        instant(r, "started_at"),
        instant(r, "finished_at"),
        r.getString("safe_error"));
  }

  /** Persists a new task; transaction scope belongs to the progress service. */
  public void insert(DockerCleanupRunEntity run) {
    db.sql(
            "insert into"
                + " docker_cleanup_run(id,repository_id,actor,dry_run,policy_version,policy_snapshot,status,started_at)"
                + " values(:id,:r,:a,:d,:v,:p,:s,:at)")
        .param("id", run.id())
        .param("r", run.repositoryId())
        .param("a", run.actor())
        .param("d", run.dryRun())
        .param("v", run.policyVersion())
        .param("p", run.policySnapshot())
        .param("s", run.status().name())
        .param("at", Timestamp.from(run.startedAt()))
        .update();
  }

  /** Returns a task if present; the calling service must check repository ownership. */
  public Optional<DockerCleanupRunEntity> findById(UUID runId) {
    return db.sql("select * from docker_cleanup_run where id=:id")
        .param("id", runId)
        .query(DockerCleanupRunRepository::run)
        .optional();
  }

  /** Reads a bounded oldest-first recovery batch. */
  public List<DockerCleanupRunEntity> findUnfinished(int limit) {
    return db.sql(
            "select * from docker_cleanup_run r where status in ('PENDING','RUNNING') or exists"
                + " (select 1 from docker_cleanup_item i where i.run_id=r.id and i.status='INTENT')"
                + " order by started_at limit :lim")
        .param("lim", limit)
        .query(DockerCleanupRunRepository::run)
        .list();
  }

  /** Persists bounded validated digest candidates atomically using one parameterized statement. */
  public void insertCandidates(UUID runId, List<String> digests) {
    if (digests.isEmpty()) return;
    db.sql(
            "insert into docker_cleanup_item(run_id,digest,status) select"
                + " :id,unnest(string_to_array(:digests,',')),'PLANNED' on conflict do nothing")
        .param("id", runId)
        .param("digests", String.join(",", digests))
        .update();
  }

  /** Updates a previously declared digest item; does not perform storage deletion. */
  public void updateItem(UUID runId, String digest, CleanupItemStatus status) {
    db.sql(
            "update docker_cleanup_item set status=:s,confirmed_at=case when :s in"
                + " ('DELETED','ALREADY_ABSENT') then now() else confirmed_at end where run_id=:id"
                + " and digest=:d")
        .param("s", status.name())
        .param("id", runId)
        .param("d", digest)
        .update();
  }

  /** Returns items bounded by the validated snapshot maximum; reads only. */
  public List<DockerCleanupItemEntity> findItems(UUID runId) {
    return db.sql("select * from docker_cleanup_item where run_id=:id order by digest")
        .param("id", runId)
        .query(
            (r, n) ->
                new DockerCleanupItemEntity(
                    r.getObject("run_id", UUID.class),
                    r.getString("digest"),
                    CleanupItemStatus.valueOf(r.getString("status")),
                    instant(r, "confirmed_at"),
                    r.getString("safe_error")))
        .list();
  }

  /** Persists a lifecycle state and optional completion timestamp in a short transaction. */
  public void updateStatus(
      UUID runId, CleanupRunStatus status, Instant finishedAt, String safeError) {
    db.sql("update docker_cleanup_run set status=:s,finished_at=:at,safe_error=:e where id=:id")
        .param("s", status.name())
        .param("at", finishedAt == null ? null : Timestamp.from(finishedAt))
        .param("e", safeError)
        .param("id", runId)
        .update();
  }
}
