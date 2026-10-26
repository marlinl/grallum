package com.grallum.application.controller;

import com.grallum.application.dto.*;
import com.grallum.application.model.*;
import com.grallum.application.service.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

/** Binds DockerCleanup HTTP inputs and delegates business decisions to its service. */
@Controller
public class DockerCleanupController {
  private final DockerCleanupService service;

  /** Retains the use-case service; construction performs no database or backend access. */
  public DockerCleanupController(DockerCleanupService service) {
    this.service = service;
  }

  /** Returns the current versioned cleanup policy through its READ-authorized use case. */
  @GetMapping("/api/repositories/{repositoryId}/policy")
  @ResponseBody
  public DockerCleanupPolicyResponse policy(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long repositoryId) {
    return service.policy(user, repositoryId);
  }

  /** Saves a validated policy with optimistic version checking and current MANAGE authorization. */
  @PutMapping("/api/repositories/{repositoryId}/policy")
  @ResponseBody
  public DockerCleanupPolicyResponse savePolicy(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable long repositoryId,
      @Valid @RequestBody DockerCleanupPolicyRequest request) {
    return service.savePolicy(user, repositoryId, request);
  }

  /**
   * Previews by default or explicitly executes cleanup; returns durable run identity and result.
   */
  @PostMapping("/api/repositories/{repositoryId}/cleanup")
  @ResponseBody
  public DockerCleanupResult cleanup(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable long repositoryId,
      @RequestParam(defaultValue = "true") boolean dryRun) {
    return dryRun ? service.preview(user, repositoryId) : service.run(user, repositoryId);
  }

  /** Returns a persisted task only within its authorized repository. */
  @GetMapping("/api/repositories/{repositoryId}/cleanup/{runId}")
  @ResponseBody
  public DockerCleanupResult result(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable long repositoryId,
      @PathVariable UUID runId) {
    return service.result(user, repositoryId, runId);
  }
}
