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

/** Owns short, ordered transactions for browser families and current credential state. */
@Service
public class UserTokenStateService {
  private final UserRepository users;
  private final UserTokenRepository tokens;
  private final AuditService audit;
  private final Clock clock;

  /** Retains persistence, audit and time collaborators; performs no queries. */
  public UserTokenStateService(
      UserRepository users, UserTokenRepository tokens, AuditService audit, Clock clock) {
    this.users = users;
    this.tokens = tokens;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Locks the account and creates a family with the supplied hashed secret and absolute deadline.
   */
  @Transactional
  public RefreshSession openSession(
      UUID userId, long credentialVersion, String refreshHash, Instant expiresAt) {
    var user = users.findByIdForUpdate(userId).orElseThrow(UserTokenStateService::denied);
    if (!user.enabled() || user.credentialVersion() != credentialVersion) throw denied();
    var id = UUID.randomUUID();
    tokens.insertFamily(
        new UserRefreshFamilyEntity(id, userId, credentialVersion, expiresAt, null));
    tokens.insertRefreshToken(
        new UserRefreshTokenEntity(UUID.randomUUID(), id, refreshHash, expiresAt, null, null));
    audit.record(
        new AuditEvent(clock.instant(), user.username(), "LOGIN", id.toString(), "SUCCESS"));
    return new RefreshSession(id, userId, credentialVersion, expiresAt);
  }

  /**
   * Serializes user, family and secret locks; replay revocation commits before the caller rejects
   * it.
   */
  @Transactional
  public RefreshRotation rotate(String presentedHash, String replacementHash, Instant now) {
    var initial = tokens.findRefresh(presentedHash);
    if (initial.isEmpty()) return result(RotationStatus.INVALID);
    var ownership = tokens.findFamily(initial.get().familyId());
    if (ownership.isEmpty()) return result(RotationStatus.INVALID);
    var user =
        users
            .findByIdForUpdate(ownership.get().userId())
            .orElseThrow(UserTokenStateService::denied);
    var family =
        tokens.findFamilyForUpdate(ownership.get().id()).orElseThrow(UserTokenStateService::denied);
    var token =
        tokens.findRefreshForUpdate(presentedHash).orElseThrow(UserTokenStateService::denied);
    if (token.consumedAt() != null) {
      tokens.revokeFamily(family.id(), now);
      audit.record(
          new AuditEvent(
              now, user.username(), "REFRESH_REPLAY", family.id().toString(), "REVOKED"));
      return result(RotationStatus.REPLAY);
    }
    if (family.revokedAt() != null
        || !user.enabled()
        || user.credentialVersion() != family.credentialVersion())
      return result(RotationStatus.INVALID);
    if (!now.isBefore(family.absoluteExpiresAt()) || !now.isBefore(token.expiresAt()))
      return result(RotationStatus.EXPIRED);
    var replacement = UUID.randomUUID();
    tokens.insertRefreshToken(
        new UserRefreshTokenEntity(
            replacement, family.id(), replacementHash, family.absoluteExpiresAt(), null, null));
    tokens.consume(token.id(), replacement, now);
    return new RefreshRotation(
        RotationStatus.ROTATED,
        Optional.of(
            new RefreshSession(
                family.id(), user.id(), user.credentialVersion(), family.absoluteExpiresAt())));
  }

  /** Revokes the located family using the same lock order; unknown secrets are idempotent. */
  @Transactional
  public void revokeFamily(String refreshHash, Instant now) {
    var token = tokens.findRefresh(refreshHash);
    if (token.isEmpty()) return;
    var ownership = tokens.findFamily(token.get().familyId());
    if (ownership.isEmpty()) return;
    var user =
        users
            .findByIdForUpdate(ownership.get().userId())
            .orElseThrow(UserTokenStateService::denied);
    var family =
        tokens.findFamilyForUpdate(ownership.get().id()).orElseThrow(UserTokenStateService::denied);
    tokens.revokeFamily(family.id(), now);
    audit.record(new AuditEvent(now, user.username(), "LOGOUT", family.id().toString(), "REVOKED"));
  }

  /** Invalidates all credentials and browser families in the account mutation transaction. */
  @Transactional
  public void invalidateUser(UUID userId) {
    users.findByIdForUpdate(userId).orElseThrow(UserTokenStateService::denied);
    users.incrementCredentialVersion(userId);
    tokens.revokeUserFamilies(userId, clock.instant());
  }

  /** Checks current account, revocation and optional browser family; returns the current entity. */
  public UserEntity requireActive(JwtClaims claims) {
    var now = clock.instant();
    var user = users.findById(claims.subject()).orElseThrow(UserTokenStateService::denied);
    if (!user.enabled()
        || user.credentialVersion() != claims.credentialVersion()
        || !now.isBefore(claims.expiresAt())
        || tokens.isTokenRevoked(claims.tokenId(), now)) throw denied();
    if (claims.familyId().isPresent()) {
      var family =
          tokens.findFamily(claims.familyId().get()).orElseThrow(UserTokenStateService::denied);
      if (!family.userId().equals(user.id())
          || family.revokedAt() != null
          || family.credentialVersion() != user.credentialVersion()
          || !now.isBefore(family.absoluteExpiresAt())) throw denied();
    }
    return user;
  }

  /** Builds a non-secret authentication failure without retaining credential input. */
  private static BusinessException denied() {
    return new BusinessException(BusinessErrorCode.UNAUTHENTICATED, "Authentication required");
  }

  /** Builds a failed rotation result without a usable session. */
  private static RefreshRotation result(RotationStatus status) {
    return new RefreshRotation(status, Optional.empty());
  }
}
