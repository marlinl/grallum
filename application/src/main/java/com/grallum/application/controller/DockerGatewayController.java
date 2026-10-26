package com.grallum.application.controller;

import com.grallum.application.config.WebProperties;
import com.grallum.application.exception.*;
import com.grallum.application.model.*;
import com.grallum.application.service.DockerGatewayService;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adapts Docker HTTP messages to typed use cases and streams owned responses with deterministic
 * closure.
 */
@Controller
public class DockerGatewayController {
  private static final Pattern ROUTE =
      Pattern.compile(
          "^/v2/([^/]+)/(.+?)/(tags/list|manifests/([^/]+)|blobs/uploads(?:/([^/]+))?/?|blobs/([^/]+))$");
  private final DockerGatewayService service;
  private final JsonMapper json;
  private final WebProperties web;

  /**
   * Retains the gateway use case, JSON mapper and trusted external origin without opening backend
   * resources.
   */
  public DockerGatewayController(DockerGatewayService service, JsonMapper json, WebProperties web) {
    this.service = service;
    this.json = json;
    this.web = web;
  }

  /**
   * Binds a canonical Docker request, invokes its use case and closes response streams and leases
   * on every path.
   */
  @RequestMapping({"/v2", "/v2/", "/v2/**"})
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      @AuthenticationPrincipal AuthenticatedUser user)
      throws IOException {
    response.setHeader("Docker-Distribution-Api-Version", "registry/2.0");
    String path = request.getRequestURI();
    DockerRequest operation = null;
    DockerResponse result;
    if (path.equals("/v2") || path.equals("/v2/")) {
      readMethod(request, true);
      result = service.ping(user, request.getMethod().equals("HEAD"));
    } else if (path.equals("/v2/_catalog")) {
      readMethod(request, false);
      var query = query(request);
      var page = service.catalog(user, page(query));
      Map<String, List<String>> headers = new HashMap<>();
      headers.put("content-type", List.of("application/json"));
      page.next()
          .ifPresent(
              next ->
                  headers.put(
                      "link",
                      List.of(
                          "</v2/_catalog?n="
                              + page(query).size()
                              + "&last="
                              + next
                              + ">; rel=\"next\"")));
      result =
          new DockerResponse(
              200,
              headers,
              new ByteArrayInputStream(
                  json.writeValueAsBytes(Map.of("repositories", page.items()))));
    } else {
      operation = parse(request);
      result = service.exchange(user, operation);
    }
    try (result) {
      if (result.status() == 401
          || result.status() == 403
          || result.status() >= 300 && result.status() < 400 && result.status() != 304)
        throw new BusinessException(
            BusinessErrorCode.DEPENDENCY_FAILURE, "Backend authentication or redirect failure");
      response.setStatus(result.status());
      for (var header : result.headers().entrySet()) {
        if (result.status() >= 400
            && (header.getKey().equalsIgnoreCase("content-length")
                || header.getKey().equalsIgnoreCase("content-type"))) continue;
        for (String value : header.getValue())
          response.addHeader(
              header.getKey(),
              header.getKey().equalsIgnoreCase("location") ? location(value, operation) : value);
      }
      if (result.status() >= 400) {
        response.setContentType("application/json");
        if (!request.getMethod().equals("HEAD"))
          response.getOutputStream().write(errorBody(result));
        return;
      }
      if (!request.getMethod().equals("HEAD") && result.status() != 204 && result.status() != 304)
        result.body().transferTo(response.getOutputStream());
    }
  }

  /** Parses only declared v2 repository operations; arbitrary upstream paths cannot be supplied. */
  private DockerRequest parse(HttpServletRequest request) throws IOException {
    var matcher = ROUTE.matcher(request.getRequestURI());
    if (!matcher.matches())
      throw new BusinessException(BusinessErrorCode.UNSUPPORTED, "Unsupported registry endpoint");
    var name = new DockerRepositoryName(matcher.group(1), matcher.group(2));
    DockerHttpMethod method;
    try {
      method = DockerHttpMethod.valueOf(request.getMethod());
    } catch (IllegalArgumentException e) {
      throw unsupported();
    }
    String resource = matcher.group(3);
    Optional<String> reference = Optional.empty();
    Optional<String> upload = Optional.empty();
    DockerOperation operation;
    if (resource.equals("tags/list")) operation = DockerOperation.TAGS_LIST;
    else if (resource.startsWith("manifests/")) {
      reference = Optional.of(matcher.group(4));
      operation =
          switch (method) {
            case GET, HEAD -> DockerOperation.MANIFEST_READ;
            case PUT -> DockerOperation.MANIFEST_WRITE;
            case DELETE -> DockerOperation.MANIFEST_DELETE;
            default -> throw unsupported();
          };
    } else if (resource.startsWith("blobs/uploads")) {
      upload = Optional.ofNullable(matcher.group(5));
      if (upload.isEmpty()) {
        if (method != DockerHttpMethod.POST) throw unsupported();
        operation = DockerOperation.UPLOAD_START;
      } else
        operation =
            switch (method) {
              case GET -> DockerOperation.UPLOAD_STATUS;
              case PATCH -> DockerOperation.UPLOAD_APPEND;
              case PUT -> DockerOperation.UPLOAD_COMPLETE;
              case DELETE -> DockerOperation.UPLOAD_CANCEL;
              default -> throw unsupported();
            };
    } else {
      reference = Optional.of(matcher.group(6));
      operation =
          switch (method) {
            case GET, HEAD -> DockerOperation.BLOB_READ;
            case DELETE -> DockerOperation.BLOB_DELETE;
            default -> throw unsupported();
          };
    }
    Map<String, List<String>> headers = new LinkedHashMap<>();
    for (String key : Collections.list(request.getHeaderNames()))
      headers.put(key, List.copyOf(Collections.list(request.getHeaders(key))));
    return new DockerRequest(
        name,
        operation,
        method,
        reference,
        upload,
        query(request),
        headers,
        request.getInputStream());
  }

  /**
   * Binds bounded unique protocol query values while preserving opaque upload state exactly once.
   */
  private static Map<String, List<String>> query(HttpServletRequest request) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    if (request.getQueryString() != null && request.getQueryString().length() > 16384)
      throw invalid();
    request
        .getParameterMap()
        .forEach(
            (key, values) -> {
              if (!Set.of("n", "last", "digest", "mount", "from", "_state").contains(key)
                  || values.length != 1) throw invalid();
              result.put(key, List.of(values));
            });
    return Map.copyOf(result);
  }

  /** Parses catalog/tag pagination using the shared size and exclusive-cursor limits. */
  private static PageQuery page(Map<String, List<String>> query) {
    try {
      return new PageQuery(
          Integer.parseInt(query.getOrDefault("n", List.of("100")).getFirst()),
          Optional.ofNullable(query.get("last")).map(List::getFirst));
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
  }

  /**
   * Rewrites only a same-repository operation-specific Location to the trusted public origin and
   * external alias.
   */
  private String location(String value, DockerRequest request) {
    if (request == null)
      throw new BusinessException(
          BusinessErrorCode.DEPENDENCY_FAILURE, "Unexpected registry location");
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(
          BusinessErrorCode.DEPENDENCY_FAILURE, "Invalid registry location");
    }
    String prefix = "/v2/" + request.repository().name() + "/";
    String path = uri.getRawPath();
    if (path == null
        || !path.startsWith(prefix)
        || uri.getRawFragment() != null
        || uri.getRawUserInfo() != null) {
      throw new BusinessException(
          BusinessErrorCode.DEPENDENCY_FAILURE, "Invalid registry location");
    }
    String suffix = path.substring(prefix.length());
    boolean valid =
        switch (request.operation()) {
          case UPLOAD_START, UPLOAD_APPEND, UPLOAD_STATUS ->
              suffix.matches("blobs/uploads/[A-Za-z0-9_-]{1,128}");
          case UPLOAD_COMPLETE -> suffix.matches("blobs/sha256:[a-f0-9]{64}");
          case MANIFEST_WRITE ->
              suffix.matches("manifests/(?:sha256:[a-f0-9]{64}|[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})");
          default -> false;
        };
    if (!valid)
      throw new BusinessException(
          BusinessErrorCode.DEPENDENCY_FAILURE, "Invalid registry location");
    return web.publicBaseUrl()
        + "/v2/"
        + request.repository().externalName()
        + "/"
        + suffix
        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
  }

  /**
   * Preserves recognized registry error codes in a bounded sanitized envelope, excluding backend
   * detail and credentials.
   */
  private byte[] errorBody(DockerResponse response) {
    String code =
        response.status() == 404
            ? "NAME_UNKNOWN"
            : response.status() == 429 ? "TOOMANYREQUESTS" : "UNKNOWN";
    try {
      byte[] bytes = response.body().readNBytes(65537);
      if (bytes.length <= 65536) {
        String supplied = json.readTree(bytes).path("errors").path(0).path("code").asText();
        if (Set.of(
                "BLOB_UNKNOWN",
                "BLOB_UPLOAD_INVALID",
                "BLOB_UPLOAD_UNKNOWN",
                "DIGEST_INVALID",
                "MANIFEST_BLOB_UNKNOWN",
                "MANIFEST_INVALID",
                "MANIFEST_UNKNOWN",
                "NAME_INVALID",
                "NAME_UNKNOWN",
                "SIZE_INVALID",
                "TAG_INVALID",
                "UNSUPPORTED",
                "TOOMANYREQUESTS")
            .contains(supplied)) code = supplied;
      }
    } catch (RuntimeException | IOException ignored) {
      code = "UNKNOWN";
    }
    return json.writeValueAsBytes(
        Map.of("errors", List.of(Map.of("code", code, "message", "Registry request failed"))));
  }

  /** Requires the supported safe method for probe and catalog endpoints. */
  private static void readMethod(HttpServletRequest request, boolean head) {
    if (!request.getMethod().equals("GET") && !(head && request.getMethod().equals("HEAD")))
      throw unsupported();
  }

  /** Produces a safe unsupported-operation failure without reflecting arbitrary path segments. */
  private static BusinessException unsupported() {
    return new BusinessException(BusinessErrorCode.UNSUPPORTED, "Unsupported registry operation");
  }

  /** Produces a safe invalid-query failure without exposing opaque upload state. */
  private static BusinessException invalid() {
    return new BusinessException(BusinessErrorCode.INVALID_INPUT, "Invalid registry query");
  }
}
