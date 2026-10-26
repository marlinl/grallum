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

/** Binds UserPermission HTTP inputs and delegates business decisions to its service. */
@Controller
public class UserPermissionController {
  private final UserAuthorizationService service;

  /** Retains the use-case service; construction performs no database or backend access. */
  public UserPermissionController(UserAuthorizationService service) {
    this.service = service;
  }

  /** Lists repository grants after a current MANAGE check. */
  @GetMapping("/api/repositories/{repositoryId}/permissions")
  @ResponseBody
  public List<UserPermissionResponse> list(
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long repositoryId) {
    return service.permissions(actor, repositoryId);
  }

  /** Saves a validated repository grant through the authorization use case. */
  @PutMapping("/api/repositories/{repositoryId}/permissions")
  @ResponseBody
  public void grant(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable long repositoryId,
      @Valid @RequestBody UserPermissionRequest request) {
    service.grant(actor, repositoryId, request);
  }

  /** Revokes an account's repository grant through the authorization use case. */
  @DeleteMapping("/api/repositories/{repositoryId}/permissions/{username}")
  @ResponseBody
  public void revoke(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable long repositoryId,
      @PathVariable String username) {
    service.revoke(actor, repositoryId, username);
  }

  /** Renders repository grants and CSRF-protected editing forms. */
  @GetMapping("/repositories/{repositoryId}/permissions")
  public String page(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable long repositoryId,
      Model model) {
    model.addAttribute("permissions", service.permissions(actor, repositoryId));
    model.addAttribute("repositoryId", repositoryId);
    return "repositories/permissions";
  }

  /** Saves a grant from a CSRF-protected form and redirects to its repository permissions page. */
  @PostMapping("/repositories/{repositoryId}/permissions")
  public String save(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable long repositoryId,
      @Valid @ModelAttribute UserPermissionRequest request) {
    service.grant(actor, repositoryId, request);
    return "redirect:/repositories/" + repositoryId + "/permissions";
  }

  /** Revokes a grant from a CSRF-protected form and redirects after success. */
  @PostMapping("/repositories/{repositoryId}/permissions/{username}/delete")
  public String delete(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable long repositoryId,
      @PathVariable String username) {
    service.revoke(actor, repositoryId, username);
    return "redirect:/repositories/" + repositoryId + "/permissions";
  }
}
