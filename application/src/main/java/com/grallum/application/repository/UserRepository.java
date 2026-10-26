package com.grallum.application.repository;

import com.grallum.application.entity.*;
import com.grallum.application.model.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Parameterized persistence for UserRepository; no HTTP or authorization decisions. */
@Repository
public class UserRepository {
  private final JdbcClient db;

  /** Retains the JDBC executor; construction performs no queries. */
  public UserRepository(JdbcClient db) {
    this.db = db;
  }

  private static final String COLUMNS =
      "id,username,password_hash,enabled,admin as administrator,credential_version";

  /** Finds an account by immutable id; no authorization decision. */
  public Optional<UserEntity> findById(UUID id) {
    return db.sql("select " + COLUMNS + " from app_user where id=:id")
        .param("id", id)
        .query(UserEntity.class)
        .optional();
  }

  /** Finds an account for credential verification; callers must not serialize the entity. */
  public Optional<UserEntity> findByUsername(String username) {
    return db.sql("select " + COLUMNS + " from app_user where username=:u")
        .param("u", username)
        .query(UserEntity.class)
        .optional();
  }

  /** Locks the user row within an existing short service transaction. */
  public Optional<UserEntity> findByIdForUpdate(UUID id) {
    return db.sql("select " + COLUMNS + " from app_user where id=:id for update")
        .param("id", id)
        .query(UserEntity.class)
        .optional();
  }

  /** Returns account entities only to an authorized management service. */
  public List<UserEntity> findAll() {
    return db.sql("select " + COLUMNS + " from app_user order by username")
        .query(UserEntity.class)
        .list();
  }

  /** Returns whether bootstrap must create the first account; reads only. */
  public boolean empty() {
    return db.sql("select count(*) from app_user").query(Long.class).single() == 0;
  }

  /** Inserts a unique account after hashing and authorization in the service. */
  public void insert(UserEntity user) {
    db.sql(
            "insert into app_user(id,username,password_hash,enabled,admin,credential_version)"
                + " values(:id,:u,:p,:e,:a,:v)")
        .param("id", user.id())
        .param("u", user.username())
        .param("p", user.passwordHash())
        .param("e", user.enabled())
        .param("a", user.administrator())
        .param("v", user.credentialVersion())
        .update();
  }

  /** Updates an already-locked account; does not choose credential invalidation policy. */
  public void update(UserEntity user) {
    db.sql("update app_user set password_hash=:p,enabled=:e,admin=:a where id=:id")
        .param("id", user.id())
        .param("p", user.passwordHash())
        .param("e", user.enabled())
        .param("a", user.administrator())
        .update();
  }

  /** Invalidates prior credential versions atomically within the management transaction. */
  public void incrementCredentialVersion(UUID userId) {
    db.sql("update app_user set credential_version=credential_version+1 where id=:id")
        .param("id", userId)
        .update();
  }
}
