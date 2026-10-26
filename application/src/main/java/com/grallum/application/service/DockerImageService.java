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

/** Reads authorized local tag views while coordinating with gateway writes and cleanup. */
@Service
public class DockerImageService {
  private final UserAuthorizationService authorization;
  private final DockerStorageService storage;
  private final DockerRepositoryLockService locks;
  private final Clock clock;

  /** Retains tag-view collaborators without acquiring locks or performing requests. */
  public DockerImageService(
      UserAuthorizationService authorization,
      DockerStorageService storage,
      DockerRepositoryLockService locks,
      Clock clock) {
    this.authorization = authorization;
    this.storage = storage;
    this.locks = locks;
    this.clock = clock;
  }

  /**
   * Lists a local tag page with validated manifests and stable observations; always releases its
   * lease.
   */
  public PageResponse<DockerTagResponse> tags(
      AuthenticatedUser user, long repositoryId, PageQuery page) {
    var repo = authorization.requireRepository(user, repositoryId, RepositoryPermission.READ);
    try (var lease = locks.acquire(repo.id(), Duration.ofSeconds(30))) {
      var tags = storage.listTags(repo, page);
      List<DockerTagResponse> result = new ArrayList<>();
      for (String tag : tags.items()) {
        var manifest = storage.readManifest(repo, tag);
        var seen = storage.observeTag(repo, tag, manifest.digest(), clock.instant());
        result.add(new DockerTagResponse(tag, manifest.digest(), seen, manifest.index()));
      }
      return new PageResponse<>(result, tags.next());
    }
  }
}
