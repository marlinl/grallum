package com.grallum.application.controller;

import com.grallum.application.config.WebProperties;
import com.grallum.application.dto.*;
import com.grallum.application.exception.*;
import com.grallum.application.model.*;
import com.grallum.application.service.UserAuthService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/**
 * Binds explicit login and token exchanges, renders authentication pages and owns browser Cookie
 * output.
 */
@Controller
public class UserAuthController {
  private final UserAuthService service;
  private final WebProperties web;
  private final Clock clock;

  /** Retains authentication and trusted browser configuration without accessing persistence. */
  public UserAuthController(UserAuthService service, WebProperties web, Clock clock) {
    this.service = service;
    this.web = web;
    this.clock = clock;
  }

  /**
   * Renders a no-JavaScript login form; Spring supplies its CSRF token during template rendering.
   */
  @GetMapping("/login")
  public String loginPage(Model model) {
    return "auth/login";
  }

  /**
   * Exchanges a validated login form, sets host-only credentials and redirects to a safe local
   * path.
   */
  @PostMapping("/login")
  public String login(
      @Valid @ModelAttribute UserLoginRequest request,
      @RequestParam(defaultValue = "/") String returnTo,
      HttpServletResponse response,
      Model model) {
    setCookies(
        response, service.login(new UserCredentials(request.username(), request.password())));
    return "redirect:" + safeReturn(returnTo);
  }

  /**
   * Rotates browser credentials independently of access-token expiry and returns to the home page.
   */
  @PostMapping("/auth/refresh")
  public String refresh(HttpServletRequest request, HttpServletResponse response) {
    setCookies(response, service.refresh(cookie(request, "grallum-refresh")));
    return "redirect:/";
  }

  /** Revokes the browser family and expires local credential and CSRF cookies. */
  @PostMapping("/auth/logout")
  public String logout(HttpServletRequest request, HttpServletResponse response) {
    service.logout(cookie(request, "grallum-refresh"));
    writeCookie(response, accessName(), "", "/", Duration.ZERO);
    writeCookie(response, "grallum-refresh", "", "/auth", Duration.ZERO);
    writeCookie(response, "XSRF-TOKEN", "", "/", Duration.ZERO);
    return "redirect:/login";
  }

  /**
   * Exchanges explicit JSON credentials for a management Bearer token without setting browser
   * credentials.
   */
  @PostMapping(value = "/api/auth/token", consumes = "application/json")
  @ResponseBody
  public TokenResponse token(@Valid @RequestBody UserLoginRequest request) {
    return TokenResponse.from(
        service.managementToken(new UserCredentials(request.username(), request.password())));
  }

  /**
   * Parses bounded Basic and repository scope inputs before invoking the registry token use case.
   */
  @GetMapping("/auth/registry/token")
  @ResponseBody
  public TokenResponse registryToken(
      @RequestParam String service,
      HttpServletRequest request,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    String[] rawScopes = request.getParameterValues("scope");
    List<String> scope = rawScopes == null ? List.of() : Arrays.asList(rawScopes);
    List<RegistryScope> scopes = new ArrayList<>();
    if (scope != null)
      for (String item : scope)
        for (String value : item.split(" +")) {
          if (value.isBlank()) continue;
          String[] fields = value.split(":", -1);
          if (fields.length != 3 || !fields[0].equals("repository")) throw invalid();
          int slash = fields[1].indexOf('/');
          if (slash < 1) throw invalid();
          var name =
              new DockerRepositoryName(
                  fields[1].substring(0, slash), fields[1].substring(slash + 1));
          Set<RegistryAction> actions = EnumSet.noneOf(RegistryAction.class);
          for (String action : fields[2].split(",")) {
            try {
              actions.add(RegistryAction.valueOf(action.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
              throw invalid();
            }
          }
          scopes.add(new RegistryScope(name, actions));
        }
    return TokenResponse.from(this.service.registryToken(service, scopes, basic(authorization)));
  }

  /** Writes bounded HttpOnly credentials and clears old CSRF state after authentication changes. */
  private void setCookies(HttpServletResponse response, LoginTokens tokens) {
    writeCookie(
        response,
        accessName(),
        tokens.accessToken().value(),
        "/",
        Duration.between(clock.instant(), tokens.accessToken().expiresAt()));
    writeCookie(
        response,
        "grallum-refresh",
        tokens.refreshSecret(),
        "/auth",
        Duration.between(clock.instant(), tokens.refreshExpiresAt()));
    writeCookie(response, "XSRF-TOKEN", "", "/", Duration.ZERO);
    response.setHeader("Cache-Control", "no-store");
  }

  /**
   * Emits a host-only browser cookie using secure defaults and the explicitly configured
   * development exception.
   */
  private void writeCookie(
      HttpServletResponse response, String name, String value, String path, Duration age) {
    response.addHeader(
        "Set-Cookie",
        ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(web.security().cookies().secure())
            .sameSite("Lax")
            .path(path)
            .maxAge(age.isNegative() ? Duration.ZERO : age)
            .build()
            .toString());
  }

  /**
   * Returns the secure host-prefixed access-cookie name or the explicit loopback development name.
   */
  private String accessName() {
    return web.security().cookies().secure() ? "__Host-grallum-access" : "grallum-access";
  }

  /** Reads a unique bounded cookie without treating duplicate credentials as authoritative. */
  private static String cookie(HttpServletRequest request, String name) {
    String value = null;
    if (request.getCookies() != null)
      for (var item : request.getCookies())
        if (item.getName().equals(name)) {
          if (value != null || item.getValue().length() > 256) throw invalid();
          value = item.getValue();
        }
    return value;
  }

  /** Parses explicit Basic syntax into short-lived credentials; does not verify passwords here. */
  private static UserCredentials basic(String header) {
    try {
      if (header == null || !header.startsWith("Basic ") || header.length() > 1024) throw invalid();
      String value =
          new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
      int colon = value.indexOf(':');
      if (colon < 1) throw invalid();
      return new UserCredentials(value.substring(0, colon), value.substring(colon + 1));
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
  }

  /**
   * Allows only simple absolute local paths, rejecting encoded redirects, authority forms and
   * control characters.
   */
  private static String safeReturn(String value) {
    return value != null && value.matches("/[A-Za-z0-9_/-]*") && !value.startsWith("//")
        ? value
        : "/";
  }

  /** Returns a safe credential-input failure without reflecting Basic header contents. */
  private static BusinessException invalid() {
    return new BusinessException(BusinessErrorCode.INVALID_INPUT, "Invalid token request");
  }
}
