package com.grallum.application.architecture;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Keeps the documented source dependency boundaries executable in the local verification lifecycle.
 */
class ArchitectureTest {
  /**
   * Rejects flat business classes, arbitrary package invention and persistence or HTTP leaking into
   * controllers.
   */
  @Test
  void enforcesLayerBoundaries() throws Exception {
    var root = Path.of("src/main/java/com/grallum/application");
    var allowed =
        Set.of(
            "controller",
            "service",
            "repository",
            "entity",
            "dto",
            "model",
            "config",
            "security",
            "task",
            "exception");
    try (var files = Files.walk(root)) {
      for (var path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        var relative = root.relativize(path);
        String source = Files.readString(path);
        if (relative.getNameCount() == 1) {
          assertEquals("Application.java", path.getFileName().toString());
          continue;
        }
        String layer = relative.getName(0).toString();
        assertTrue(allowed.contains(layer), path.toString());
        assertTrue(
            source.startsWith("package com.grallum.application." + layer + ";"), path.toString());
        if (layer.equals("controller"))
          for (String forbidden :
              List.of(".repository.", "JdbcClient", "java.net.http", "DockerRepositoryLockService"))
            assertFalse(source.contains(forbidden), path + " imports " + forbidden);
        if (layer.equals("service")) {
          assertFalse(source.contains("jakarta.servlet"), path.toString());
          assertFalse(source.contains("JdbcClient"), path.toString());
        }
        if (layer.equals("repository"))
          for (String forbidden :
              List.of(".service.", ".controller.", "jakarta.servlet", "java.net.http"))
            assertFalse(source.contains(forbidden), path.toString());
        if (layer.equals("task")) {
          assertFalse(source.contains(".repository."), path.toString());
          assertFalse(source.contains("java.net.http"), path.toString());
        }
        if (source.contains("import java.net.http"))
          assertTrue(
              Set.of(
                      "DockerStorageService.java",
                      "DockerProxyService.java",
                      "ApplicationConfig.java")
                  .contains(path.getFileName().toString()),
              path.toString());
      }
    }
  }
}
