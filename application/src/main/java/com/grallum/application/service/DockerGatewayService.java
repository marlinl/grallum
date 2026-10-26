package com.grallum.application.service;

import com.grallum.application.config.*;
import com.grallum.application.dto.*;
import com.grallum.application.entity.*;
import com.grallum.application.exception.*;
import com.grallum.application.model.*;
import com.grallum.application.repository.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authorizes typed Docker operations and coordinates backend response ownership for the request
 * lifetime.
 */
@Service
public class DockerGatewayService {
  private final UserAuthorizationService authorization;
  private final DockerRepositoryService repositories;
  private final DockerRepositoryLockService locks;
  private final DockerStorageService storage;
  private final DockerProxyService proxy;
  private final JsonMapper json;
  private final Clock clock;

  /** Retains protocol-use-case collaborators without creating HTTP or database resources. */
  public DockerGatewayService(
      UserAuthorizationService authorization,
      DockerRepositoryService repositories,
      DockerRepositoryLockService locks,
      DockerStorageService storage,
      DockerProxyService proxy,
      JsonMapper json,
      Clock clock) {
    this.authorization = authorization;
    this.repositories = repositories;
    this.locks = locks;
    this.storage = storage;
    this.proxy = proxy;
    this.json = json;
    this.clock = clock;
  }

  /** Returns the authenticated v2 probe without contacting an image backend. */
  public DockerResponse ping(AuthenticatedUser user, boolean head) {
    authorization.requireActive(user);
    return new DockerResponse(
        200,
        Map.of("content-type", List.of("application/json")),
        new ByteArrayInputStream(head ? new byte[0] : "{}".getBytes(StandardCharsets.UTF_8)));
  }

  /** Projects the current visible catalog to external Docker repository names. */
  public PageResponse<String> catalog(AuthenticatedUser user, PageQuery page) {
    var result = repositories.list(user, page);
    return new PageResponse<>(
        result.items().stream().map(DockerRepositoryResponse::imagePrefix).toList(), result.next());
  }

  /**
   * Checks operation-specific permissions, acquires a lease and returns a response that owns that
   * lease.
   */
  public DockerResponse exchange(AuthenticatedUser user, DockerRequest request) {
    RepositoryPermission permission =
        switch (request.operation()) {
          case TAGS_LIST, MANIFEST_READ, BLOB_READ -> RepositoryPermission.READ;
          case MANIFEST_DELETE, BLOB_DELETE -> RepositoryPermission.MANAGE;
          default -> RepositoryPermission.WRITE;
        };
    var repo = authorization.requireRepository(user, request.repository(), permission);
    DockerStorageService.validate(repo, request);
    var lease = locks.acquire(repo.id(), Duration.ofSeconds(30));
    try {
      DockerResponse result;
      if (request.operation() == DockerOperation.TAGS_LIST) {
        int size;
        try {
          size = Integer.parseInt(request.query().getOrDefault("n", List.of("100")).getFirst());
        } catch (RuntimeException e) {
          throw new BusinessException(BusinessErrorCode.INVALID_INPUT, "Invalid page size");
        }
        var page =
            new PageQuery(
                size, Optional.ofNullable(request.query().get("last")).map(List::getFirst));
        var tags =
            repo.mode() == DockerRegistryMode.PROXY
                ? proxy.cachedTags(repo, page)
                : storage.listTags(repo, page);
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("content-type", List.of("application/json"));
        tags.next()
            .ifPresent(
                last ->
                    headers.put(
                        "link",
                        List.of(
                            "</v2/"
                                + repo.name().externalName()
                                + "/tags/list?n="
                                + size
                                + "&last="
                                + last
                                + ">; rel=\"next\"")));
        result =
            new DockerResponse(
                200,
                headers,
                new ByteArrayInputStream(
                    json.writeValueAsBytes(
                        Map.of("name", repo.name().externalName(), "tags", tags.items()))));
      } else
        result =
            repo.mode() == DockerRegistryMode.PROXY
                ? proxy.pull(repo, request)
                : storage.exchange(repo, request);
      if (result.status() >= 200
          && result.status() < 300
          && (request.operation() == DockerOperation.MANIFEST_READ
              || request.operation() == DockerOperation.MANIFEST_WRITE)
          && request.reference().isPresent()
          && !request.reference().get().startsWith("sha256:")) {
        var digest = result.headers().get("docker-content-digest");
        if (digest != null) {
          try {
            storage.observeTag(repo, request.reference().get(), digest.getFirst(), clock.instant());
          } catch (RuntimeException e) {
            System.getLogger(getClass().getName())
                .log(
                    System.Logger.Level.WARNING,
                    "Manifest succeeded; observation will be reconciled by a later complete"
                        + " snapshot");
          }
        }
      }
      return result.own(lease);
    } catch (RuntimeException e) {
      lease.close();
      throw e;
    }
  }
}
