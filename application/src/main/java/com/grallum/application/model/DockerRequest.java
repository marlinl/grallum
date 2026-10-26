package com.grallum.application.model;

import java.io.InputStream;
import java.time.*;
import java.util.*;

/** Validated single-use Docker operation; request body remains owned by its caller. */
public record DockerRequest(
    DockerRepositoryName repository,
    DockerOperation operation,
    DockerHttpMethod method,
    Optional<String> reference,
    Optional<String> uploadId,
    Map<String, List<String>> query,
    Map<String, List<String>> headers,
    InputStream body) {
  /** Copies header/query values and requires an operation/body; no I/O. */
  public DockerRequest {
    Objects.requireNonNull(repository);
    Objects.requireNonNull(operation);
    Objects.requireNonNull(method);
    Objects.requireNonNull(reference);
    Objects.requireNonNull(uploadId);
    Objects.requireNonNull(body);
    query = copy(query);
    headers = copy(headers);
  }

  /** Deep-copies string lists; prevents later request mutation; pure. */
  private static Map<String, List<String>> copy(Map<String, List<String>> values) {
    var result = new LinkedHashMap<String, List<String>>();
    values.forEach((k, v) -> result.put(k, List.copyOf(v)));
    return Collections.unmodifiableMap(result);
  }

  /** Returns only nonsecret request classification; does not expose opaque upload state. */
  @Override
  public String toString() {
    return "DockerRequest[" + operation + ", " + repository.externalName() + "]";
  }
}
