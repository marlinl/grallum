package com.grallum.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Bootstraps the Java 25 Spring Boot application and its configured infrastructure. */
@SpringBootApplication(
    exclude =
        org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class Application {

  /** Starts HTTP services and Flyway; args are Spring overrides; returns nothing. */
  static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
