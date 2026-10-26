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

/** Coordinates administrator account mutations, password hashing and credential invalidation. */
@Service
public class UserManagementService {
  private final UserAuthorizationService authorization;
  private final UserRepository users;
  private final UserTokenStateService state;
  private final org.springframework.security.crypto.password.PasswordEncoder encoder;
  private final AuditService audit;
  private final Clock clock;

  /** Retains user-management collaborators without accessing storage. */
  public UserManagementService(
      UserAuthorizationService authorization,
      UserRepository users,
      UserTokenStateService state,
      org.springframework.security.crypto.password.PasswordEncoder encoder,
      AuditService audit,
      Clock clock) {
    this.authorization = authorization;
    this.users = users;
    this.state = state;
    this.encoder = encoder;
    this.audit = audit;
    this.clock = clock;
  }

  /** Lists safe user DTOs after checking current administrator permission. */
  public List<UserResponse> list(AuthenticatedUser actor) {
    authorization.requireAdministrator(actor);
    return users.findAll().stream().map(UserManagementService::response).toList();
  }

  /** Creates a unique account with a validated password hash and audit event. */
  @Transactional
  public UserResponse create(AuthenticatedUser actor, UserCreateRequest request) {
    authorization.requireAdministrator(actor);
    return insert(actor.username(), request.username(), request.password(), request.admin());
  }

  /**
   * Updates account flags and optional password under a row lock; credential changes revoke
   * sessions.
   */
  @Transactional
  public UserResponse update(AuthenticatedUser actor, String username, UserUpdateRequest request) {
    authorization.requireAdministrator(actor);
    var found =
        users
            .findByUsername(username)
            .orElseThrow(
                () -> new BusinessException(BusinessErrorCode.NOT_FOUND, "User not found"));
    var user = users.findByIdForUpdate(found.id()).orElseThrow();
    if (user.id().equals(actor.id()) && (!request.enabled() || !request.admin()))
      throw new BusinessException(
          BusinessErrorCode.CONFLICT, "Cannot disable or demote your own account");
    String hash = user.passwordHash();
    if (request.password() != null) {
      validatePassword(request.password());
      hash = encoder.encode(request.password());
    }
    var updated =
        new UserEntity(
            user.id(),
            user.username(),
            hash,
            request.enabled(),
            request.admin(),
            user.credentialVersion());
    users.update(updated);
    if (request.password() != null || user.enabled() != request.enabled())
      state.invalidateUser(user.id());
    audit.record(
        new AuditEvent(clock.instant(), actor.username(), "UPDATE_USER", username, "SUCCESS"));
    return response(updated);
  }

  /**
   * Creates the explicit initial administrator only for an empty database; never resets existing
   * passwords.
   */
  @Transactional
  public void bootstrap(String username, String password) {
    if (users.empty()) insert("bootstrap", username, password, true);
  }

  /** Validates and persists a new account with a non-secret audit result. */
  private UserResponse insert(String actor, String username, String password, boolean admin) {
    if (username == null || !username.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
      throw new BusinessException(BusinessErrorCode.INVALID_INPUT, "Invalid username");
    validatePassword(password);
    if (users.findByUsername(username).isPresent())
      throw new BusinessException(BusinessErrorCode.CONFLICT, "Username already exists");
    var user =
        new UserEntity(UUID.randomUUID(), username, encoder.encode(password), true, admin, 0);
    users.insert(user);
    audit.record(new AuditEvent(clock.instant(), actor, "CREATE_USER", username, "SUCCESS"));
    return response(user);
  }

  /** Requires a nonblank password of at least twelve characters and at most 72 UTF-8 bytes. */
  private static void validatePassword(String password) {
    if (password == null
        || password.isBlank()
        || password.length() < 12
        || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw new BusinessException(
          BusinessErrorCode.INVALID_INPUT,
          "Password must contain 12 characters and at most 72 UTF-8 bytes");
  }

  /** Projects an entity into a response without hashes, versions or token state. */
  private static UserResponse response(UserEntity user) {
    return new UserResponse(user.id(), user.username(), user.enabled(), user.administrator());
  }
}
