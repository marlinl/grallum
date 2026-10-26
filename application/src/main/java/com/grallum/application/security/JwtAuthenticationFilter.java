package com.grallum.application.security;

import com.grallum.application.config.WebProperties;
import com.grallum.application.exception.*;
import com.grallum.application.model.*;
import com.grallum.application.service.UserAuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Extracts audience-specific request credentials and adapts verified identities to Spring Security.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
  private final UserAuthService service;
  private final TokenAudience audience;
  private final WebProperties web;

  /** Retains authentication and trusted transport configuration without querying account state. */
  public JwtAuthenticationFilter(
      UserAuthService service, TokenAudience audience, WebProperties web) {
    this.service = service;
    this.audience = audience;
    this.web = web;
  }

  /**
   * Authenticates explicit Bearer or permitted management Cookie credentials without resource Basic
   * fallback.
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      String authorization = request.getHeader("Authorization");
      String bearer = null;
      String cookie = null;
      if (authorization != null) {
        if (!authorization.startsWith("Bearer ")
            || authorization.length() > 16391
            || authorization.substring(7).isBlank()) throw denied();
        bearer = authorization.substring(7);
      }
      if (audience == TokenAudience.MANAGEMENT && request.getCookies() != null)
        for (var item : request.getCookies())
          if (item.getName()
              .equals(
                  web.security().cookies().secure() ? "__Host-grallum-access" : "grallum-access")) {
            if (cookie != null) throw denied();
            cookie = item.getValue();
          }
      String token = bearer != null ? bearer : cookie;
      if (token != null) {
        var user = service.authenticate(token, audience);
        if (bearer != null && cookie != null) {
          var second = service.authenticate(cookie, audience);
          if (!second.id().equals(user.id())) throw denied();
        }
        var authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                user,
                null,
                List.of(
                    new SimpleGrantedAuthority(user.administrator() ? "ROLE_ADMIN" : "ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      }
    } catch (BusinessException e) {
      HttpErrorWriter.write(request, response, e, web.publicBaseUrl().toString());
      return;
    } catch (org.springframework.dao.DataAccessException e) {
      HttpErrorWriter.write(
          request,
          response,
          new BusinessException(BusinessErrorCode.UNAVAILABLE, "Authentication state unavailable"),
          web.publicBaseUrl().toString());
      return;
    }
    chain.doFilter(request, response);
  }

  /** Returns a credential-format failure without reflecting supplied header or cookie values. */
  private static BusinessException denied() {
    return new BusinessException(BusinessErrorCode.UNAUTHENTICATED, "Invalid credentials");
  }
}
