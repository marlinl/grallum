package com.grallum.application.service;

import com.grallum.application.config.*;
import com.grallum.application.dto.*;
import com.grallum.application.entity.*;
import com.grallum.application.exception.*;
import com.grallum.application.model.*;
import com.grallum.application.repository.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Provides public-registry pull-through reads and local-cache-only tag enumeration. */
@Service
public class DockerProxyService {
  private final DockerProperties properties;
  private final HttpClient http;
  private final DockerStorageService storage;

  /** Retains the trusted proxy transport and local metadata service; performs no requests. */
  public DockerProxyService(
      DockerProperties properties, HttpClient http, DockerStorageService storage) {
    this.properties = properties;
    this.http = http;
    this.storage = storage;
  }

  /**
   * Streams only manifest/blob reads through the configured proxy without forwarding user
   * credentials.
   */
  public DockerResponse pull(DockerRepositoryRef repository, DockerRequest request) {
    if (repository.mode() != DockerRegistryMode.PROXY
        || request.operation() != DockerOperation.MANIFEST_READ
            && request.operation() != DockerOperation.BLOB_READ)
      throw new BusinessException(
          BusinessErrorCode.UNSUPPORTED, "Proxy supports pull operations only");
    DockerStorageService.validate(repository, request);
    try {
      var response =
          http.send(
              DockerStorageService.build(
                  properties.registries().get(repository.name().registry()).url(),
                  request,
                  properties.registry().transferTimeout()),
              HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() == 401
          || response.statusCode() == 403
          || response.statusCode() >= 300
              && response.statusCode() < 400
              && response.statusCode() != 304) {
        response.body().close();
        throw DockerStorageService.dependency();
      }
      return new DockerResponse(
          response.statusCode(),
          DockerStorageService.responseHeaders(response),
          response.body(),
          properties.registry().transferTimeout());
    } catch (HttpTimeoutException e) {
      throw new BusinessException(BusinessErrorCode.TIMEOUT, "Proxy request timed out");
    } catch (IOException e) {
      throw DockerStorageService.dependency();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(BusinessErrorCode.CANCELLED, "Proxy request interrupted");
    }
  }

  /** Lists cached tags from the local management endpoint without triggering an upstream pull. */
  public PageResponse<String> cachedTags(DockerRepositoryRef repository, PageQuery page) {
    if (repository.mode() != DockerRegistryMode.PROXY)
      throw new BusinessException(BusinessErrorCode.INVALID_INPUT, "Proxy repository required");
    return storage.listTags(repository, page);
  }
}
