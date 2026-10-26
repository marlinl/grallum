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
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Streams private registry operations and reads local-only metadata for either registry mode. */
@Service
public class DockerStorageService {
  private static final String ACCEPT =
      "application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json,"
          + " application/vnd.docker.distribution.manifest.list.v2+json,"
          + " application/vnd.docker.distribution.manifest.v2+json";
  private static final Set<String> REQUEST_HEADERS =
      Set.of(
          "accept",
          "content-type",
          "content-range",
          "range",
          "if-match",
          "if-none-match",
          "if-modified-since",
          "if-unmodified-since");
  private static final Set<String> RESPONSE_HEADERS =
      Set.of(
          "content-type",
          "content-length",
          "content-range",
          "range",
          "etag",
          "last-modified",
          "docker-content-digest",
          "docker-upload-uuid",
          "location",
          "link",
          "retry-after",
          "accept-ranges");
  private final DockerProperties properties;
  private final HttpClient http;
  private final JsonMapper json;
  private final DockerTagObservationRepository observations;

  /** Retains configured transport and local observation persistence without initiating I/O. */
  public DockerStorageService(
      DockerProperties properties,
      HttpClient http,
      JsonMapper json,
      DockerTagObservationRepository observations) {
    this.properties = properties;
    this.http = http;
    this.json = json;
    this.observations = observations;
  }

  /**
   * Streams an authorized private operation; the caller owns response closure and repository
   * coordination.
   */
  public DockerResponse exchange(DockerRepositoryRef repository, DockerRequest request) {
    if (repository.mode() != DockerRegistryMode.PRIVATE)
      throw new BusinessException(
          BusinessErrorCode.UNSUPPORTED, "Private registry operation required");
    validate(repository, request);
    return send(
        build(
            properties.registries().get(repository.name().registry()).url(),
            request,
            properties.registry().transferTimeout()),
        false);
  }

  /** Lists one bounded page from the local metadata endpoint, including proxy-cache management. */
  public PageResponse<String> listTags(DockerRepositoryRef repository, PageQuery page) {
    page.after().ifPresent(DockerStorageService::tag);
    String suffix =
        "/tags/list?n=" + page.size() + page.after().map(value -> "&last=" + value).orElse("");
    try (var response = metadata(repository, suffix, "GET")) {
      if (response.status() == 404) return new PageResponse<>(List.of(), Optional.empty());
      requireSuccess(response.status());
      var root = json.readTree(bytes(response));
      var nodes = root.get("tags");
      List<String> tags = new ArrayList<>();
      if (nodes != null && !nodes.isNull()) {
        if (!nodes.isArray()) throw dependency();
        for (var node : nodes) {
          if (!node.isString()) throw dependency();
          String value = node.asText();
          tag(value);
          tags.add(value);
        }
      }
      if (tags.size() > page.size()) throw dependency();
      String previous = page.after().orElse("");
      for (String value : tags) {
        if (value.compareTo(previous) <= 0) throw dependency();
        previous = value;
      }
      boolean more = response.headers().containsKey("link");
      if (more && tags.isEmpty()) throw dependency();
      return new PageResponse<>(tags, more ? Optional.of(tags.getLast()) : Optional.empty());
    } catch (BusinessException e) {
      throw e;
    } catch (RuntimeException e) {
      throw dependency();
    }
  }

  /** Enumerates a complete local snapshot or fails without returning partial tags. */
  public List<String> listAllTags(DockerRepositoryRef repository) {
    List<String> result = new ArrayList<>();
    Optional<String> after = Optional.empty();
    do {
      var page = listTags(repository, new PageQuery(1000, after));
      result.addAll(page.items());
      if (result.size() > 100000) throw dependency();
      after = page.next();
    } while (after.isPresent());
    return List.copyOf(result);
  }

  /**
   * Reads a bounded schema-two manifest, validates its digest and returns typed index dependencies.
   */
  public DockerManifest readManifest(DockerRepositoryRef repository, String reference) {
    reference(reference);
    try (var response = metadata(repository, "/manifests/" + reference, "GET")) {
      if (response.status() == 404)
        throw new BusinessException(BusinessErrorCode.NOT_FOUND, "Manifest not found");
      requireSuccess(response.status());
      byte[] body = bytes(response);
      String digest =
          response.headers().getOrDefault("docker-content-digest", List.of("")).getFirst();
      digest(digest);
      String actual =
          "sha256:"
              + HexFormat.of()
                  .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
      if (!actual.equals(digest) || reference.startsWith("sha256:") && !reference.equals(digest))
        throw dependency();
      var root = json.readTree(body);
      String media = root.path("mediaType").asText();
      boolean index =
          media.equals("application/vnd.oci.image.index.v1+json")
              || media.equals("application/vnd.docker.distribution.manifest.list.v2+json");
      if (root.path("schemaVersion").asInt() != 2
          || !index
              && !media.equals("application/vnd.oci.image.manifest.v1+json")
              && !media.equals("application/vnd.docker.distribution.manifest.v2+json"))
        throw dependency();
      List<DockerManifestReference> references = new ArrayList<>();
      if (index) {
        var children = root.path("manifests");
        if (!children.isArray() || children.size() > 10000) throw dependency();
        for (var child : children) {
          String childDigest = child.path("digest").asText();
          digest(childDigest);
          String childMedia = child.path("mediaType").asText();
          if (!Set.of(
                  "application/vnd.oci.image.index.v1+json",
                  "application/vnd.oci.image.manifest.v1+json",
                  "application/vnd.docker.distribution.manifest.list.v2+json",
                  "application/vnd.docker.distribution.manifest.v2+json")
              .contains(childMedia)) throw dependency();
          references.add(new DockerManifestReference(childDigest, childMedia));
        }
      }
      return new DockerManifest(digest, media, index, references);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw dependency();
    }
  }

  /**
   * Deletes a validated local digest and distinguishes confirmed deletion from confirmed absence.
   */
  public DockerDeleteResult deleteManifest(DockerRepositoryRef repository, String digest) {
    digest(digest);
    try (var response = metadata(repository, "/manifests/" + digest, "DELETE")) {
      if (response.status() == 202) return DockerDeleteResult.DELETED;
      if (response.status() == 404) return DockerDeleteResult.ALREADY_ABSENT;
      throw dependency();
    }
  }

  /** Atomically records an observed tag and returns its stable first-seen timestamp. */
  @Transactional
  public Instant observeTag(
      DockerRepositoryRef repository, String tag, String digest, Instant observedAt) {
    tag(tag);
    digest(digest);
    return observations.upsert(repository.id(), tag, digest, observedAt).firstSeen();
  }

  /**
   * Commits a complete observation snapshot atomically; callers must not pass a partial
   * enumeration.
   */
  @Transactional
  public List<DockerTag> synchronizeObservations(
      DockerRepositoryRef repository, List<DockerTaggedManifest> completeTags, Instant observedAt) {
    List<DockerTag> result = new ArrayList<>();
    Set<String> present = new HashSet<>();
    for (var item : completeTags) {
      tag(item.tag());
      digest(item.manifest().digest());
      if (!present.add(item.tag())) throw dependency();
      var observation =
          observations.upsert(repository.id(), item.tag(), item.manifest().digest(), observedAt);
      result.add(
          new DockerTag(
              item.tag(),
              item.manifest().digest(),
              observation.firstSeen(),
              item.manifest().index()));
    }
    observations.removeMissing(repository.id(), present);
    return List.copyOf(result);
  }

  /** Validates typed operation and repository consistency before any trusted-endpoint request. */
  static void validate(DockerRepositoryRef repository, DockerRequest request) {
    if (!repository.name().equals(request.repository()))
      throw new BusinessException(BusinessErrorCode.INVALID_INPUT, "Repository mismatch");
    var method = request.method();
    boolean valid =
        switch (request.operation()) {
          case TAGS_LIST -> method == DockerHttpMethod.GET;
          case MANIFEST_READ, BLOB_READ ->
              method == DockerHttpMethod.GET || method == DockerHttpMethod.HEAD;
          case MANIFEST_WRITE, UPLOAD_COMPLETE -> method == DockerHttpMethod.PUT;
          case MANIFEST_DELETE, BLOB_DELETE, UPLOAD_CANCEL -> method == DockerHttpMethod.DELETE;
          case UPLOAD_START -> method == DockerHttpMethod.POST;
          case UPLOAD_STATUS -> method == DockerHttpMethod.GET;
          case UPLOAD_APPEND -> method == DockerHttpMethod.PATCH;
        };
    if (!valid)
      throw new BusinessException(BusinessErrorCode.UNSUPPORTED, "Unsupported registry method");
    switch (request.operation()) {
      case MANIFEST_READ, MANIFEST_WRITE ->
          reference(request.reference().orElseThrow(DockerStorageService::invalid));
      case MANIFEST_DELETE, BLOB_READ, BLOB_DELETE ->
          digest(request.reference().orElseThrow(DockerStorageService::invalid));
      case UPLOAD_STATUS, UPLOAD_APPEND, UPLOAD_COMPLETE, UPLOAD_CANCEL -> {
        if (!request.uploadId().orElse("").matches("[A-Za-z0-9_-]{1,128}")) throw invalid();
      }
      default -> {}
    }
    if (request.operation() == DockerOperation.UPLOAD_COMPLETE
        || request.query().containsKey("digest")) {
      var values = request.query().get("digest");
      if (values == null || values.size() != 1) throw invalid();
      digest(values.getFirst());
    }
    int size = 0;
    for (var entry : request.query().entrySet()) {
      size += entry.getKey().length();
      for (var value : entry.getValue()) size += value.length();
    }
    if (size > 16384) throw invalid();
  }

  /**
   * Builds a streaming request using a trusted origin, validated path and an explicit
   * request-header allowlist.
   */
  static HttpRequest build(URI origin, DockerRequest request, Duration timeout) {
    String suffix =
        switch (request.operation()) {
          case TAGS_LIST -> "/tags/list";
          case MANIFEST_READ, MANIFEST_WRITE, MANIFEST_DELETE ->
              "/manifests/" + request.reference().orElseThrow();
          case BLOB_READ, BLOB_DELETE -> "/blobs/" + request.reference().orElseThrow();
          case UPLOAD_START -> "/blobs/uploads/";
          default -> "/blobs/uploads/" + request.uploadId().orElseThrow();
        };
    List<String> query = new ArrayList<>();
    request
        .query()
        .forEach(
            (key, values) -> {
              if (request.operation() == DockerOperation.UPLOAD_START
                  && (key.equals("mount") || key.equals("from"))) return;
              for (String value : values) query.add(encode(key) + "=" + encode(value));
            });
    var uri =
        origin.resolve(
            "/v2/"
                + request.repository().name()
                + suffix
                + (query.isEmpty() ? "" : "?" + String.join("&", query)));
    var builder = HttpRequest.newBuilder(uri).timeout(timeout);
    request
        .headers()
        .forEach(
            (key, values) -> {
              if (REQUEST_HEADERS.contains(key.toLowerCase(Locale.ROOT)))
                values.forEach(value -> builder.header(key, value));
            });
    var body =
        switch (request.method()) {
          case POST, PATCH, PUT -> HttpRequest.BodyPublishers.ofInputStream(request::body);
          default -> HttpRequest.BodyPublishers.noBody();
        };
    return builder.method(request.method().name(), body).build();
  }

  /** Projects backend headers without forwarding cookies, authentication or hop-by-hop fields. */
  static Map<String, List<String>> responseHeaders(HttpResponse<?> response) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    response
        .headers()
        .map()
        .forEach(
            (key, value) -> {
              if (RESPONSE_HEADERS.contains(key.toLowerCase(Locale.ROOT)))
                result.put(key.toLowerCase(Locale.ROOT), List.copyOf(value));
            });
    return Map.copyOf(result);
  }

  /**
   * Sends a local metadata request to the private endpoint or the mandatory proxy management
   * endpoint.
   */
  private DockerResponse metadata(DockerRepositoryRef repository, String suffix, String method) {
    var config = properties.registries().get(repository.name().registry());
    if (config == null || config.mode() != repository.mode()) throw dependency();
    URI origin =
        repository.mode() == DockerRegistryMode.PROXY ? config.managementUrl() : config.url();
    return send(
        HttpRequest.newBuilder(origin.resolve("/v2/" + repository.name().name() + suffix))
            .timeout(properties.registry().metadataTimeout())
            .header("Accept", ACCEPT)
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build(),
        true);
  }

  /**
   * Executes an HTTP request and transfers response-stream ownership without following redirects.
   */
  private DockerResponse send(HttpRequest request, boolean metadata) {
    try {
      var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      return new DockerResponse(
          response.statusCode(),
          responseHeaders(response),
          response.body(),
          metadata
              ? properties.registry().metadataTimeout()
              : properties.registry().transferTimeout());
    } catch (java.net.http.HttpTimeoutException e) {
      throw new BusinessException(BusinessErrorCode.TIMEOUT, "Registry request timed out");
    } catch (IOException e) {
      throw dependency();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(BusinessErrorCode.CANCELLED, "Registry request interrupted");
    }
  }

  /**
   * Reads metadata up to the configured cap, failing rather than accepting an incomplete document.
   */
  private byte[] bytes(DockerResponse response) {
    try {
      byte[] bytes = response.body().readNBytes(properties.registry().metadataMaxBytes() + 1);
      if (bytes.length > properties.registry().metadataMaxBytes()) throw dependency();
      return bytes;
    } catch (IOException e) {
      throw dependency();
    }
  }

  /** Rejects unsuccessful local metadata responses without leaking backend details. */
  private static void requireSuccess(int status) {
    if (status < 200 || status >= 300) throw dependency();
  }

  /** Validates a canonical SHA-256 digest before using it in an endpoint or observation. */
  static void digest(String value) {
    if (value == null || !value.matches("sha256:[a-f0-9]{64}")) throw invalid();
  }

  /** Validates a supported tag identifier without URL decoding or path normalization. */
  static void tag(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")) throw invalid();
  }

  /** Accepts a supported tag or canonical content digest. */
  static void reference(String value) {
    if (value != null && value.startsWith("sha256:")) digest(value);
    else tag(value);
  }

  /** Encodes a single query component without treating opaque upload state as a path. */
  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** Returns a safe backend failure without including endpoint URLs or response bodies. */
  static BusinessException dependency() {
    return new BusinessException(BusinessErrorCode.DEPENDENCY_FAILURE, "Registry operation failed");
  }

  /** Returns a safe invalid-input error without reflecting path or token content. */
  private static BusinessException invalid() {
    return new BusinessException(BusinessErrorCode.INVALID_INPUT, "Invalid registry request");
  }
}
