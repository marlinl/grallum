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

/** Enforces current account, repository grants, signed scopes and configured backend mode. */
@Service
public class UserAuthorizationService {
  private final UserTokenStateService state;
  private final UserRepository users;
  private final UserPermissionRepository permissions;
  private final DockerRepository repositories;
  private final DockerProperties properties;
  private final AuditService audit;
  private final Clock clock;

  /** Retains authorization collaborators; construction has no persistence effects. */
  public UserAuthorizationService(
      UserTokenStateService state,
      UserRepository users,
      UserPermissionRepository permissions,
      DockerRepository repositories,
      DockerProperties properties,
      AuditService audit,
      Clock clock) {
    this.state = state;
    this.users = users;
    this.permissions = permissions;
    this.repositories = repositories;
    this.properties = properties;
    this.audit = audit;
    this.clock = clock;
  }

  /** Rechecks credential state and refreshes the caller's current account role. */
  public AuthenticatedUser requireActive(AuthenticatedUser user) {
    if (user == null || !user.id().equals(user.claims().subject()))
      throw new BusinessException(BusinessErrorCode.UNAUTHENTICATED, "Authentication required");
    var current = state.requireActive(user.claims());
    return new AuthenticatedUser(
        current.id(), current.username(), current.administrator(), user.claims());
  }

  /** Requires the current management audience and administrator role. */
  public void requireAdministrator(AuthenticatedUser user) {
    var current = requireActive(user);
    if (current.claims().audience() != TokenAudience.MANAGEMENT || !current.administrator())
      throw forbidden();
  }

  /** Resolves a canonical repository and verifies live permission and signed registry scope. */
  public DockerRepositoryRef requireRepository(
      AuthenticatedUser user, DockerRepositoryName name, RepositoryPermission permission) {
    var current = requireActive(user);
    var repo = repositories.findByName(name).orElseThrow(() -> missing(current));
    return authorize(current, repo, permission);
  }

  /**
   * Resolves an internal repository id for management requests after checking current credentials.
   */
  public DockerRepositoryRef requireRepository(
      AuthenticatedUser user, long repositoryId, RepositoryPermission permission) {
    var current = requireActive(user);
    if (current.claims().audience() != TokenAudience.MANAGEMENT) throw forbidden();
    return authorize(
        current,
        repositories.findById(repositoryId).orElseThrow(() -> missing(current)),
        permission);
  }

  /**
   * Intersects requested scopes with current ACL and mode after the caller verifies the user's
   * password.
   */
  public List<RegistryGrant> registryGrants(UUID userId, List<RegistryScope> requested) {
    var user =
        users
            .findById(userId)
            .filter(UserEntity::enabled)
            .orElseThrow(UserAuthorizationService::forbidden);
    Map<DockerRepositoryName, Set<RegistryAction>> grants = new LinkedHashMap<>();
    for (var scope : requested) {
      var repo = repositories.findByName(scope.repository());
      if (repo.isEmpty()) continue;
      var configured = properties.registries().get(scope.repository().registry());
      if (configured == null) continue;
      var permission =
          user.administrator()
              ? RepositoryPermission.MANAGE
              : permissions
                  .find(userId, repo.get().id())
                  .map(UserPermissionEntity::permission)
                  .orElse(null);
      if (permission == null) continue;
      var allowed =
          grants.computeIfAbsent(scope.repository(), key -> EnumSet.noneOf(RegistryAction.class));
      for (var action : scope.actions())
        if (permission.ordinal() >= required(action).ordinal()
            && (configured.mode() == DockerRegistryMode.PRIVATE || action == RegistryAction.PULL))
          allowed.add(action);
    }
    return grants.entrySet().stream()
        .filter(e -> !e.getValue().isEmpty())
        .map(e -> new RegistryGrant(e.getKey(), e.getValue()))
        .toList();
  }

  /** Returns non-secret grants after requiring current MANAGE permission. */
  public List<UserPermissionResponse> permissions(AuthenticatedUser actor, long repositoryId) {
    requireRepository(actor, repositoryId, RepositoryPermission.MANAGE);
    return permissions.findByRepositoryId(repositoryId).stream()
        .map(
            p ->
                new UserPermissionResponse(
                    users
                        .findById(p.userId())
                        .orElseThrow(UserAuthorizationService::forbidden)
                        .username(),
                    p.permission()))
        .toList();
  }

  /** Saves a repository grant and audit record in one short transaction. */
  @Transactional
  public void grant(AuthenticatedUser actor, long repositoryId, UserPermissionRequest request) {
    requireRepository(actor, repositoryId, RepositoryPermission.MANAGE);
    var user =
        users
            .findByUsername(request.username())
            .orElseThrow(
                () -> new BusinessException(BusinessErrorCode.NOT_FOUND, "User not found"));
    permissions.save(
        new UserPermissionEntity(
            repositoryId, user.id(), Objects.requireNonNull(request.permission())));
    audit.record(
        new AuditEvent(
            clock.instant(),
            actor.username(),
            "GRANT",
            Long.toString(repositoryId),
            request.permission().name()));
  }

  /** Removes a repository grant and audits the committed authorization change. */
  @Transactional
  public void revoke(AuthenticatedUser actor, long repositoryId, String username) {
    requireRepository(actor, repositoryId, RepositoryPermission.MANAGE);
    var user =
        users
            .findByUsername(username)
            .orElseThrow(
                () -> new BusinessException(BusinessErrorCode.NOT_FOUND, "User not found"));
    permissions.delete(user.id(), repositoryId);
    audit.record(
        new AuditEvent(
            clock.instant(), actor.username(), "REVOKE", Long.toString(repositoryId), username));
  }

  /**
   * Applies current ACL, token scope and proxy write restrictions without trusting cached roles.
   */
  private DockerRepositoryRef authorize(
      AuthenticatedUser user, DockerRepositoryEntity repo, RepositoryPermission permission) {
    var name = new DockerRepositoryName(repo.registry(), repo.name());
    var configured = properties.registries().get(repo.registry());
    if (configured == null)
      throw new BusinessException(BusinessErrorCode.UNAVAILABLE, "Registry unavailable");
    var actual =
        user.administrator()
            ? RepositoryPermission.MANAGE
            : permissions
                .find(user.id(), repo.id())
                .map(UserPermissionEntity::permission)
                .orElse(null);
    if (actual == null || actual.ordinal() < permission.ordinal()) throw forbidden();
    if (user.claims().audience() == TokenAudience.REGISTRY) {
      if (configured.mode() == DockerRegistryMode.PROXY && permission != RepositoryPermission.READ)
        throw new BusinessException(
            BusinessErrorCode.UNSUPPORTED, "Proxy repositories are read-only");
      var action =
          permission == RepositoryPermission.READ
              ? RegistryAction.PULL
              : permission == RepositoryPermission.WRITE
                  ? RegistryAction.PUSH
                  : RegistryAction.DELETE;
      if (user.claims().grants().stream()
          .noneMatch(g -> g.repository().equals(name) && g.actions().contains(action)))
        throw forbidden();
    }
    return new DockerRepositoryRef(repo.id(), name, configured.mode());
  }

  /** Maps a registry action to the minimum repository permission. */
  private static RepositoryPermission required(RegistryAction action) {
    return switch (action) {
      case PULL -> RepositoryPermission.READ;
      case PUSH -> RepositoryPermission.WRITE;
      case DELETE -> RepositoryPermission.MANAGE;
    };
  }

  /** Hides unknown repository existence from non-administrators. */
  private static BusinessException missing(AuthenticatedUser user) {
    return user.administrator()
        ? new BusinessException(BusinessErrorCode.NOT_FOUND, "Repository not found")
        : forbidden();
  }

  /** Returns a safe permission failure without exposing account or repository data. */
  private static BusinessException forbidden() {
    return new BusinessException(BusinessErrorCode.FORBIDDEN, "Permission denied");
  }
}
