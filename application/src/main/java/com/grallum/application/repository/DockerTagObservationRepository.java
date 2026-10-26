package com.grallum.application.repository;

import com.grallum.application.entity.*;
import com.grallum.application.model.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Parameterized persistence for DockerTagObservationRepository; no HTTP or authorization decisions.
 */
@Repository
public class DockerTagObservationRepository {
  private final JdbcClient db;

  /** Retains the JDBC executor; construction performs no queries. */
  public DockerTagObservationRepository(JdbcClient db) {
    this.db = db;
  }

  /** Atomically records a digest observation; unchanged content preserves its previous age. */
  public DockerTagObservationEntity upsert(
      long repositoryId, String tag, String digest, Instant observedAt) {
    return db.sql(
            "insert into tag_observation(repo_id,tag,digest,first_seen) values(:id,:t,:d,:at) on"
                + " conflict(repo_id,tag) do update set digest=excluded.digest,first_seen=case when"
                + " tag_observation.digest=excluded.digest then tag_observation.first_seen else"
                + " excluded.first_seen end returning repo_id,tag,digest,first_seen")
        .param("id", repositoryId)
        .param("t", tag)
        .param("d", digest)
        .param("at", Timestamp.from(observedAt))
        .query(
            (r, n) ->
                new DockerTagObservationEntity(
                    r.getLong("repo_id"),
                    r.getString("tag"),
                    r.getString("digest"),
                    r.getTimestamp("first_seen").toInstant()))
        .single();
  }

  /** Removes stale observations only after the caller has a complete successful local snapshot. */
  public void removeMissing(long repositoryId, Set<String> presentTags) {
    if (presentTags.isEmpty()) {
      db.sql("delete from tag_observation where repo_id=:id").param("id", repositoryId).update();
      return;
    }
    db.sql("delete from tag_observation where repo_id=:id and tag not in (:tags)")
        .param("id", repositoryId)
        .param("tags", presentTags)
        .update();
  }

  /** Deletes observations after confirmed storage deletion; never deletes image files. */
  public void deleteByDigest(long repositoryId, String digest) {
    db.sql("delete from tag_observation where repo_id=:id and digest=:d")
        .param("id", repositoryId)
        .param("d", digest)
        .update();
  }
}
