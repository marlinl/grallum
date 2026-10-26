package com.grallum.application.controller;

import com.grallum.application.dto.*;
import com.grallum.application.model.*;
import com.grallum.application.service.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

/** Binds User HTTP inputs and delegates business decisions to its service. */
@Controller
public class UserController {
  private final UserManagementService service;

  /** Retains the use-case service; construction performs no database or backend access. */
  public UserController(UserManagementService service) {
    this.service = service;
  }

  /** Lists non-secret user views after a live administrator check. */
  @GetMapping("/api/users")
  @ResponseBody
  public List<UserResponse> list(@AuthenticationPrincipal AuthenticatedUser actor) {
    return service.list(actor);
  }

  /** Creates a user from validated JSON through the administrator use case. */
  @PostMapping("/api/users")
  @ResponseBody
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public UserResponse create(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @Valid @RequestBody UserCreateRequest request) {
    return service.create(actor, request);
  }

  /** Updates a user and optional credentials through the current administrator use case. */
  @PutMapping("/api/users/{username}")
  @ResponseBody
  public UserResponse update(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable String username,
      @Valid @RequestBody UserUpdateRequest request) {
    return service.update(actor, username, request);
  }
}
