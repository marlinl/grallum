package com.grallum.application.config;

import com.grallum.application.service.UserManagementService;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** Shared infrastructure wiring; contains no business policy. */
@Configuration(proxyBeanMethods = false)
public class ApplicationConfig {
  /** Supplies the production UTC clock; tests may replace it. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /** Builds a trusted endpoint HTTP tool with redirects disabled; no requests are sent. */
  @Bean
  public HttpClient dockerHttpClient(DockerProperties properties) {
    return HttpClient.newBuilder()
        .connectTimeout(properties.registry().connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  /** Supplies one bounded JSON mapper for metadata and HTTP response conversion. */
  @Bean
  public JsonMapper jsonMapper() {
    return JsonMapper.builder().build();
  }

  /** Invokes first-account bootstrap through the business service after migrations. */
  @Bean
  public ApplicationRunner bootstrap(UserManagementService users, WebProperties properties) {
    return args ->
        users.bootstrap(properties.bootstrap().username(), properties.bootstrap().password());
  }
}
