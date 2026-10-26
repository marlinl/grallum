package com.grallum.application.repository;

import com.grallum.application.entity.*;
import com.grallum.application.model.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Parameterized persistence for UserTokenRepository; no HTTP or authorization decisions. */
@Repository
public class UserTokenRepository {
  private final JdbcClient db;

  /** Retains the JDBC executor; construction performs no queries. */
  public UserTokenRepository(JdbcClient db) {
    this.db = db;
  }

  /** Maps a nullable database timestamp to UTC; has no side effects. */
  private static Instant instant(ResultSet row, String column) throws SQLException {
    var value = row.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  /** Maps a family row; called only by JDBC queries. */
  private static UserRefreshFamilyEntity family(ResultSet row, int number) throws SQLException {
    return new UserRefreshFamilyEntity(
        row.getObject("id", UUID.class),
        row.getObject("user_id", UUID.class),
        row.getLong("credential_version"),
        instant(row, "absolute_expires_at"),
        instant(row, "revoked_at"));
  }

  /** Maps a refresh credential row without exposing its secret hash. */
  private static UserRefreshTokenEntity token(ResultSet row, int number) throws SQLException {
    return new UserRefreshTokenEntity(
        row.getObject("id", UUID.class),
        row.getObject("family_id", UUID.class),
        row.getString("secret_hash"),
        instant(row, "expires_at"),
        instant(row, "consumed_at"),
        row.getObject("replacement_id", UUID.class));
  }

  /** Saves a browser family in the active state transaction. */
  public void insertFamily(UserRefreshFamilyEntity family) {
    db.sql(
            "insert into user_refresh_family(id,user_id,credential_version,absolute_expires_at)"
                + " values(:id,:u,:v,:e)")
        .param("id", family.id())
        .param("u", family.userId())
        .param("v", family.credentialVersion())
        .param("e", Timestamp.from(family.absoluteExpiresAt()))
        .update();
  }

  /** Saves only the hashed refresh secret; participates in the active transaction. */
  public void insertRefreshToken(UserRefreshTokenEntity token) {
    db.sql(
            "insert into user_refresh_token(id,family_id,secret_hash,expires_at)"
                + " values(:id,:f,:h,:e)")
        .param("id", token.id())
        .param("f", token.familyId())
        .param("h", token.secretHash())
        .param("e", Timestamp.from(token.expiresAt()))
        .update();
  }

  /** Locates ownership before acquiring ordered transaction locks; does not consume. */
  public Optional<UserRefreshTokenEntity> findRefresh(String secretHash) {
    return db.sql("select * from user_refresh_token where secret_hash=:h")
        .param("h", secretHash)
        .query(UserTokenRepository::token)
        .optional();
  }

  /** Locks a refresh record after its user and family have been locked. */
  public Optional<UserRefreshTokenEntity> findRefreshForUpdate(String secretHash) {
    return db.sql("select * from user_refresh_token where secret_hash=:h for update")
        .param("h", secretHash)
        .query(UserTokenRepository::token)
        .optional();
  }

  /** Reads a login family for live JWT authorization. */
  public Optional<UserRefreshFamilyEntity> findFamily(UUID familyId) {
    return db.sql("select * from user_refresh_family where id=:id")
        .param("id", familyId)
        .query(UserTokenRepository::family)
        .optional();
  }

  /** Locks a login family after its user row, maintaining the shared lock order. */
  public Optional<UserRefreshFamilyEntity> findFamilyForUpdate(UUID familyId) {
    return db.sql("select * from user_refresh_family where id=:id for update")
        .param("id", familyId)
        .query(UserTokenRepository::family)
        .optional();
  }

  /** Marks a locked refresh token consumed and points at its replacement. */
  public void consume(UUID tokenId, UUID replacementId, Instant consumedAt) {
    db.sql(
            "update user_refresh_token set consumed_at=:at,replacement_id=:replacement where id=:id"
                + " and consumed_at is null")
        .param("at", Timestamp.from(consumedAt))
        .param("replacement", replacementId)
        .param("id", tokenId)
        .update();
  }

  /** Persists family revocation without throwing after the mutation. */
  public void revokeFamily(UUID familyId, Instant revokedAt) {
    db.sql("update user_refresh_family set revoked_at=coalesce(revoked_at,:at) where id=:id")
        .param("at", Timestamp.from(revokedAt))
        .param("id", familyId)
        .update();
  }

  /** Revokes all device families in the credential-change transaction. */
  public void revokeUserFamilies(UUID userId, Instant revokedAt) {
    db.sql("update user_refresh_family set revoked_at=coalesce(revoked_at,:at) where user_id=:u")
        .param("at", Timestamp.from(revokedAt))
        .param("u", userId)
        .update();
  }

  /** Checks the unexpired explicit token revocation set; reads only. */
  public boolean isTokenRevoked(UUID tokenId, Instant now) {
    return db.sql(
            "select exists(select 1 from user_token_revocation where token_id=:id and"
                + " expires_at>:at)")
        .param("id", tokenId)
        .param("at", Timestamp.from(now))
        .query(Boolean.class)
        .single();
  }
}
