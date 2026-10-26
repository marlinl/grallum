package com.grallum.application.controller;

import com.grallum.application.dto.*;
import com.grallum.application.model.*;
import com.grallum.application.service.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/** Binds DockerCleanupPage HTTP inputs and delegates business decisions to its service. */
@Controller
public class DockerCleanupPageController {
  private final DockerCleanupService service;

  /** Retains the use-case service; construction performs no database or backend access. */
  public DockerCleanupPageController(DockerCleanupService service) {
    this.service = service;
  }

  /** Renders current policy and separate preview and execution forms. */
  @GetMapping("/repositories/{repositoryId}/cleanup")
  public String page(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable long repositoryId,
      Model model) {
    model.addAttribute("policy", service.policy(user, repositoryId));
    model.addAttribute("repositoryId", repositoryId);
    return "repositories/cleanup";
  }

  /** Runs a CSRF-protected preview and renders its durable result. */
  @PostMapping("/repositories/{repositoryId}/cleanup/preview")
  public String preview(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable long repositoryId,
      Model model) {
    model.addAttribute("result", service.preview(user, repositoryId));
    return page(user, repositoryId, model);
  }

  /** Executes a separately confirmed cleanup against freshly read state and renders its result. */
  @PostMapping("/repositories/{repositoryId}/cleanup/run")
  public String run(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable long repositoryId,
      Model model) {
    model.addAttribute("result", service.run(user, repositoryId));
    return page(user, repositoryId, model);
  }

  /** Saves a CSRF-protected versioned policy form and follows Post/Redirect/Get. */
  @PostMapping("/repositories/{repositoryId}/cleanup/policy")
  public String save(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable long repositoryId,
      @Valid @ModelAttribute DockerCleanupPolicyRequest request) {
    service.savePolicy(user, repositoryId, request);
    return "redirect:/repositories/" + repositoryId + "/cleanup";
  }
}
