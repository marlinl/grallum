package com.grallum.application.service;

import com.grallum.application.config.*;
import com.grallum.application.dto.*;
import com.grallum.application.entity.*;
import com.grallum.application.exception.*;
import com.grallum.application.model.*;
import com.grallum.application.repository.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registers configured repositories and projects caller-visible catalog and management views. */
@Service
public class DockerRepositoryService {
  private final UserAuthorizationService authorization;
  private final DockerRepository repositories;
  private final DockerProperties properties;
  private final AuditService audit;
  private final Clock clock;

  /** Retains repository, authorization and audit collaborators without reading storage. */
  public DockerRepositoryService(
      UserAuthorizationService authorization,
      DockerRepository repositories,
      DockerProperties properties,
      AuditService audit,
      Clock clock) {
    this.authorization = authorization;
    this.repositories = repositories;
    this.properties = properties;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Lists visible registered repositories in external-name order with a stable exclusive cursor.
   */
  public PageResponse<DockerRepositoryResponse> list(AuthenticatedUser user, PageQuery page) {
    var current = authorization.requireActive(user);
    var after = page.after().map(DockerRepositoryService::name);
    List<DockerRepositoryResponse> visible = new ArrayList<>();
    Optional<String> cursor = page.after();
    boolean exhausted = false;
    while (visible.size() <= page.size() && !exhausted) {
      var rows =
          repositories.findVisible(
              current.id(),
              current.administrator(),
              cursor.map(DockerRepositoryService::name),
              page.size() + 1);
      exhausted = rows.size() < page.size() + 1;
      for (var row : rows) {
        cursor = Optional.of(row.registry() + "/" + row.name());
        if (!properties.registries().containsKey(row.registry())) continue;
        if (current.claims().audience() == TokenAudience.REGISTRY
            && current.claims().grants().stream()
                .noneMatch(
                    g ->
                        g.repository().externalName().equals(row.registry() + "/" + row.name())
                            && g.actions().contains(RegistryAction.PULL))) continue;
        visible.add(response(row));
        if (visible.size() > page.size()) break;
      }
    }
    boolean more = visible.size() > page.size();
    var items = visible.subList(0, Math.min(page.size(), visible.size()));
    return new PageResponse<>(
        items, more ? Optional.of(items.getLast().imagePrefix()) : Optional.empty());
  }

  /** Returns a safe repository view after a current READ check. */
  public DockerRepositoryResponse detail(AuthenticatedUser user, long repositoryId) {
    var ref = authorization.requireRepository(user, repositoryId, RepositoryPermission.READ);
    return new DockerRepositoryResponse(
        ref.id(), ref.name().registry(), ref.name().name(), ref.mode(), ref.name().externalName());
  }

  /**
   * Registers a configured repository and audits it atomically; duplicate names produce a conflict.
   */
  @Transactional
  public DockerRepositoryResponse create(AuthenticatedUser user, DockerRepositoryRequest request) {
    authorization.requireAdministrator(user);
    var name = new DockerRepositoryName(request.registry(), request.name());
    if (!properties.registries().containsKey(name.registry()))
      throw new BusinessException(BusinessErrorCode.INVALID_INPUT, "Unknown registry alias");
    if (repositories.findByName(name).isPresent())
      throw new BusinessException(BusinessErrorCode.CONFLICT, "Repository already registered");
    var repo = repositories.insert(name.registry(), name.name());
    audit.record(
        new AuditEvent(
            clock.instant(),
            user.username(),
            "REGISTER_REPOSITORY",
            name.externalName(),
            "SUCCESS"));
    return response(repo);
  }

  /** Maps a configured entity to a DTO without exposing trusted backend origins. */
  private DockerRepositoryResponse response(DockerRepositoryEntity row) {
    return new DockerRepositoryResponse(
        row.id(),
        row.registry(),
        row.name(),
        properties.registries().get(row.registry()).mode(),
        row.registry() + "/" + row.name());
  }

  /** Parses an external catalog cursor into its canonical alias and repository components. */
  private static DockerRepositoryName name(String value) {
    int slash = value.indexOf('/');
    if (slash < 1)
      throw new BusinessException(BusinessErrorCode.INVALID_INPUT, "Invalid catalog cursor");
    return new DockerRepositoryName(value.substring(0, slash), value.substring(slash + 1));
  }
}
