package com.grallum.application.repository;

import com.grallum.application.entity.*;
import com.grallum.application.model.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Parameterized persistence for DockerCleanupPolicyRepository; no HTTP or authorization decisions.
 */
@Repository
public class DockerCleanupPolicyRepository {
  private final JdbcClient db;

  /** Retains the JDBC executor; construction performs no queries. */
  public DockerCleanupPolicyRepository(JdbcClient db) {
    this.db = db;
  }

  /** Reads an optional persisted policy; the service owns safe defaults. */
  public Optional<DockerCleanupPolicyEntity> findByRepositoryId(long repositoryId) {
    return db.sql(
            "select repo_id as"
                + " repository_id,keep_latest,older_than_days,protected_pattern,enabled,version"
                + " from cleanup_policy where repo_id=:id")
        .param("id", repositoryId)
        .query(DockerCleanupPolicyEntity.class)
        .optional();
  }

  /** Saves only at the expected revision; returns false on concurrent modification. */
  public boolean save(DockerCleanupPolicyEntity policy, long expectedVersion) {
    if (expectedVersion == 0)
      return db.sql(
                  "insert into"
                      + " cleanup_policy(repo_id,keep_latest,older_than_days,protected_pattern,enabled,version)"
                      + " values(:id,:k,:d,:p,:e,1) on conflict(repo_id) do nothing")
              .param("id", policy.repositoryId())
              .param("k", policy.keepLatest())
              .param("d", policy.olderThanDays())
              .param("p", policy.protectedPattern())
              .param("e", policy.enabled())
              .update()
          == 1;
    return db.sql(
                "update cleanup_policy set"
                    + " keep_latest=:k,older_than_days=:d,protected_pattern=:p,enabled=:e,version=version+1"
                    + " where repo_id=:id and version=:v")
            .param("id", policy.repositoryId())
            .param("k", policy.keepLatest())
            .param("d", policy.olderThanDays())
            .param("p", policy.protectedPattern())
            .param("e", policy.enabled())
            .param("v", expectedVersion)
            .update()
        == 1;
  }

  /** Returns a bounded stable-id page for the scheduling service. */
  public List<Long> findEnabledRepositoryIds(long afterId, int limit) {
    return db.sql(
            "select repo_id from cleanup_policy where enabled=true and repo_id>:after order by"
                + " repo_id limit :lim")
        .param("after", afterId)
        .param("lim", limit)
        .query(Long.class)
        .list();
  }
}
