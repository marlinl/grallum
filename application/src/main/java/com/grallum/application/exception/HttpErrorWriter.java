package com.grallum.application.exception;

import jakarta.servlet.http.*;
import java.io.IOException;

/**
 * Renders safe HTTP errors for MVC and security boundaries without exposing dependency messages.
 */
public final class HttpErrorWriter {
  /** Prevents construction of this stateless transport formatter. */
  private HttpErrorWriter() {}

  /**
   * Writes a safe JSON failure or login redirect, preserving Docker challenge and HEAD semantics.
   */
  public static void write(
      HttpServletRequest request,
      HttpServletResponse response,
      BusinessException error,
      String publicBase)
      throws IOException {
    if (response.isCommitted()) return;
    int status = status(error.code());
    boolean docker = request.getRequestURI().startsWith("/v2");
    if (status == 401
        && !docker
        && !request.getRequestURI().startsWith("/api/")
        && !request.getRequestURI().startsWith("/auth/")) {
      response.sendRedirect("/login");
      return;
    }
    response.resetBuffer();
    response.setHeader("Content-Length", null);
    response.setStatus(status);
    response.setHeader("Cache-Control", "no-store");
    response.setContentType("application/json");
    if (docker) {
      response.setHeader("Docker-Distribution-Api-Version", "registry/2.0");
      if (status == 401) response.setHeader("WWW-Authenticate", challenge(request, publicBase));
    }
    if ("HEAD".equals(request.getMethod())) return;
    String code = docker ? dockerCode(error.code()) : error.code().name();
    String message =
        switch (status) {
          case 400 -> "Invalid request";
          case 401 -> "Authentication required";
          case 403 -> "Permission denied";
          case 404 -> "Resource not found";
          case 405 -> "Unsupported operation";
          case 409 -> "State conflict; reload and retry";
          case 429 -> "Too many requests";
          case 504 -> "Dependency timed out";
          default -> "Service unavailable";
        };
    String payload =
        docker
            ? "{\"errors\":[{\"code\":\""
                + code
                + "\",\"message\":\""
                + message
                + "\",\"detail\":null}]}"
            : "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}";
    response.getOutputStream().write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /**
   * Maps internal errors to the fixed Docker error vocabulary without exposing exception messages.
   */
  private static String dockerCode(BusinessErrorCode code) {
    return switch (code) {
      case UNAUTHENTICATED -> "UNAUTHORIZED";
      case FORBIDDEN -> "DENIED";
      case NOT_FOUND -> "NAME_UNKNOWN";
      case INVALID_INPUT -> "NAME_INVALID";
      case UNSUPPORTED -> "UNSUPPORTED";
      case RATE_LIMITED -> "TOOMANYREQUESTS";
      default -> "UNKNOWN";
    };
  }

  /** Maps business failures to bounded public HTTP status codes. */
  public static int status(BusinessErrorCode code) {
    return switch (code) {
      case INVALID_INPUT -> 400;
      case UNAUTHENTICATED -> 401;
      case FORBIDDEN -> 403;
      case NOT_FOUND -> 404;
      case CONFLICT -> 409;
      case UNSUPPORTED -> 405;
      case RATE_LIMITED -> 429;
      case DEPENDENCY_FAILURE -> 502;
      case TIMEOUT -> 504;
      default -> 503;
    };
  }

  /** Builds a trusted-origin registry challenge with a canonical optional repository scope. */
  private static String challenge(HttpServletRequest request, String publicBase) {
    String value =
        "Bearer realm=\"" + publicBase + "/auth/registry/token\",service=\"grallum-registry\"";
    String path = request.getRequestURI();
    var matcher =
        java.util.regex.Pattern.compile(
                "^/v2/([a-z0-9-]+/[a-z0-9_./-]+)/(?:manifests|blobs|tags)/.*$")
            .matcher(path);
    if (matcher.matches() && path.length() < 1024) {
      String name = matcher.group(1);
      String action =
          path.contains("/blobs/uploads/")
              ? "pull,push"
              : request.getMethod().equals("GET") || request.getMethod().equals("HEAD")
                  ? "pull"
                  : request.getMethod().equals("DELETE") ? "delete" : "pull,push";
      value += ",scope=\"repository:" + name + ":" + action + "\"";
    }
    return value;
  }
}
