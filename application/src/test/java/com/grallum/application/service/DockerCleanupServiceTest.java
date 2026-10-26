package com.grallum.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.grallum.application.exception.BusinessException;
import com.grallum.application.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Exercises retention safety using fixed complete snapshots without database or HTTP dependencies.
 */
class DockerCleanupServiceTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  /** Builds the pure planner without invoking any injected collaborator. */
  private DockerCleanupService service() {
    return new DockerCleanupService(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  /** Creates a deterministic aged tag fixture. */
  private DockerTag tag(String name, String digest, int days, boolean index) {
    return new DockerTag(name, digest, NOW.minus(Duration.ofDays(days)), index);
  }

  /**
   * Retained aliases protect their shared digest while genuinely old unprotected digests remain
   * candidates.
   */
  @Test
  void protectsSharedDigestsAndRecentTags() {
    var tags =
        List.of(
            tag("latest", "shared", 90, false),
            tag("old", "shared", 90, false),
            tag("recent", "new", 1, false),
            tag("obsolete", "delete", 90, false));
    var plan = service().plan(tags, new CleanupPolicy(1, 30, "latest", true, 4), NOW, Set.of());
    assertEquals(List.of("delete"), plan.candidateDigests());
    assertTrue(
        plan.decisions().stream()
            .filter(d -> d.digest().equals("shared"))
            .findFirst()
            .orElseThrow()
            .reasons()
            .contains(RetentionReason.SHARED_DIGEST));
    assertEquals(4, plan.policyVersion());
  }

  /** Index roots and referenced manifests remain protected even when all their tags are old. */
  @Test
  void protectsIndexGraphsAndAgeBoundary() {
    var tags =
        List.of(
            tag("recent", "new", 1, false),
            tag("index", "root", 90, true),
            tag("child", "child", 90, false),
            tag("boundary", "edge", 30, false),
            tag("stale", "old", 31, false));
    assertEquals(
        List.of("old"),
        service()
            .plan(tags, new CleanupPolicy(1, 30, "", true, 0), NOW, Set.of("child"))
            .candidateDigests());
  }

  /** Empty snapshots are safe and unsupported unbounded policies fail before producing a plan. */
  @Test
  void handlesEmptyAndRejectsInvalidPolicy() {
    assertTrue(
        service()
            .plan(List.of(), new CleanupPolicy(1, 30, "release-*", false, 0), NOW, Set.of())
            .candidateDigests()
            .isEmpty());
    assertThrows(
        BusinessException.class,
        () -> service().plan(List.of(), new CleanupPolicy(0, 30, "", true, 0), NOW, Set.of()));
  }
}
