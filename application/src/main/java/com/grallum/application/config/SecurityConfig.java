package com.grallum.application.config;

import com.grallum.application.exception.*;
import com.grallum.application.model.TokenAudience;
import com.grallum.application.security.JwtAuthenticationFilter;
import com.grallum.application.service.UserAuthService;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * Defines stateless credential, Docker Bearer and browser-management chains with explicit CSRF
 * boundaries.
 */
@Configuration
public class SecurityConfig {
  private final WebProperties web;

  /** Retains trusted public-origin and development-cookie configuration. */
  public SecurityConfig(WebProperties web) {
    this.web = web;
  }

  /** Creates the password encoder; raw passwords never enter persistence. */
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  /**
   * Protects browser credential operations with CSRF while permitting explicit JSON and Basic
   * exchanges.
   */
  @Bean
  @Order(1)
  SecurityFilterChain credentialSecurity(HttpSecurity http) throws Exception {
    common(http);
    http.securityMatcher("/login", "/auth/**", "/api/auth/token")
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    http.csrf(
        csrf ->
            csrf.csrfTokenRepository(csrfRepository())
                .sessionAuthenticationStrategy(
                    new org.springframework.security.web.authentication.session
                        .NullAuthenticatedSessionStrategy())
                .ignoringRequestMatchers(
                    request ->
                        request.getRequestURI().equals("/api/auth/token")
                            && request.getMethod().equals("POST")
                            && request.getContentType() != null
                            && request
                                .getContentType()
                                .split(";")[0]
                                .equalsIgnoreCase("application/json")));
    return http.build();
  }

  /**
   * Accepts only registry-audience Bearer tokens for Docker resources, with protocol error
   * formatting.
   */
  @Bean
  @Order(2)
  SecurityFilterChain dockerSecurity(HttpSecurity http, UserAuthService service) throws Exception {
    common(http);
    http.securityMatcher("/v2", "/v2/**")
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
    http.addFilterBefore(
        new JwtAuthenticationFilter(service, TokenAudience.REGISTRY, web),
        UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  /**
   * Accepts management Bearer or secure browser Cookies and retains CSRF for ambient Cookie
   * requests.
   */
  @Bean
  @Order(3)
  SecurityFilterChain managementSecurity(HttpSecurity http, UserAuthService service)
      throws Exception {
    common(http);
    http.authorizeHttpRequests(
        auth ->
            auth.requestMatchers("/assets/**", "/error").permitAll().anyRequest().authenticated());
    http.csrf(
        csrf ->
            csrf.csrfTokenRepository(csrfRepository())
                .sessionAuthenticationStrategy(
                    new org.springframework.security.web.authentication.session
                        .NullAuthenticatedSessionStrategy())
                .ignoringRequestMatchers(
                    request ->
                        request.getHeader("Authorization") != null
                            && request.getHeader("Authorization").startsWith("Bearer ")));
    http.addFilterBefore(
        new JwtAuthenticationFilter(service, TokenAudience.MANAGEMENT, web),
        UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  /**
   * Configures stateless sessions and safe audience-aware authentication and CSRF error responses.
   */
  private void common(HttpSecurity http) throws Exception {
    http.sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable())
        .logout(logout -> logout.disable());
    http.exceptionHandling(
        errors ->
            errors
                .authenticationEntryPoint(
                    (request, response, error) ->
                        HttpErrorWriter.write(
                            request,
                            response,
                            new BusinessException(
                                BusinessErrorCode.UNAUTHENTICATED, "Authentication required"),
                            web.publicBaseUrl().toString()))
                .accessDeniedHandler(
                    (request, response, error) ->
                        HttpErrorWriter.write(
                            request,
                            response,
                            new BusinessException(BusinessErrorCode.FORBIDDEN, "Permission denied"),
                            web.publicBaseUrl().toString())));
  }

  /**
   * Creates host-only CSRF cookies with explicit secure and SameSite attributes and no HttpSession.
   */
  private CookieCsrfTokenRepository csrfRepository() {
    var repository = new CookieCsrfTokenRepository();
    repository.setCookieCustomizer(
        cookie -> cookie.secure(web.security().cookies().secure()).sameSite("Lax").path("/"));
    return repository;
  }
}
