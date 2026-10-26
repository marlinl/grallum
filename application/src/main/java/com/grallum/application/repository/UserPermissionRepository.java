package com.grallum.application.repository;

import com.grallum.application.entity.*;
import com.grallum.application.model.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Parameterized persistence for UserPermissionRepository; no HTTP or authorization decisions. */
@Repository
public class UserPermissionRepository {
  private final JdbcClient db;

  /** Retains the JDBC executor; construction performs no queries. */
  public UserPermissionRepository(JdbcClient db) {
    this.db = db;
  }

  /** Reads the user's current repository grant, if any. */
  public Optional<UserPermissionEntity> find(UUID userId, long repositoryId) {
    return db.sql(
            "select repo_id as repository_id,user_id,permission from repo_permission where"
                + " user_id=:u and repo_id=:r")
        .param("u", userId)
        .param("r", repositoryId)
        .query(UserPermissionEntity.class)
        .optional();
  }

  /** Lists persisted grants; authorization is required before this query. */
  public List<UserPermissionEntity> findByRepositoryId(long repositoryId) {
    return db.sql(
            "select repo_id as repository_id,user_id,permission from repo_permission where"
                + " repo_id=:r order by user_id")
        .param("r", repositoryId)
        .query(UserPermissionEntity.class)
        .list();
  }

  /** Upserts a validated grant in the service transaction. */
  public void save(UserPermissionEntity permission) {
    db.sql(
            "insert into repo_permission(repo_id,user_id,permission) values(:r,:u,:p) "
                + "on conflict(repo_id,user_id) do update set permission=excluded.permission")
        .param("r", permission.repositoryId())
        .param("u", permission.userId())
        .param("p", permission.permission().name())
        .update();
  }

  /** Removes one grant in the service transaction. */
  public void delete(UUID userId, long repositoryId) {
    db.sql("delete from repo_permission where user_id=:u and repo_id=:r")
        .param("u", userId)
        .param("r", repositoryId)
        .update();
  }
}
