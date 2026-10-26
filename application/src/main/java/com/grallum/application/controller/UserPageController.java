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

/** Binds UserPage HTTP inputs and delegates business decisions to its service. */
@Controller
public class UserPageController {
  private final UserManagementService service;

  /** Retains the use-case service; construction performs no database or backend access. */
  public UserPageController(UserManagementService service) {
    this.service = service;
  }

  /** Renders administrator-visible account DTOs without password hashes or token state. */
  @GetMapping("/users")
  public String list(@AuthenticationPrincipal AuthenticatedUser actor, Model model) {
    model.addAttribute("users", service.list(actor));
    return "users/list";
  }

  /** Updates an account from a CSRF-protected form and redirects after success. */
  @PostMapping("/users/{username}")
  public String save(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable String username,
      @ModelAttribute UserUpdateRequest request,
      Model model) {
    service.update(
        actor,
        username,
        new UserUpdateRequest(
            request.enabled(),
            request.admin(),
            request.password() == null || request.password().isEmpty()
                ? null
                : request.password()));
    return "redirect:/users";
  }

  /** Creates a validated user from a CSRF-protected form and redirects after success. */
  @PostMapping("/users")
  public String create(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @Valid @ModelAttribute UserCreateRequest request) {
    service.create(actor, request);
    return "redirect:/users";
  }
}
