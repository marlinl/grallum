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

/** Binds DockerRepositoryPage HTTP inputs and delegates business decisions to its service. */
@Controller
public class DockerRepositoryPageController {
  private final DockerRepositoryService service;

  /** Retains the use-case service; construction performs no database or backend access. */
  public DockerRepositoryPageController(DockerRepositoryService service) {
    this.service = service;
  }

  /** Renders the authorized repository page and its stable next-page cursor. */
  @GetMapping({"/", "/repositories"})
  public String list(@AuthenticationPrincipal AuthenticatedUser user, PageQuery page, Model model) {
    var result = service.list(user, page);
    model.addAttribute("repositories", result);
    model.addAttribute("repositoriesEmpty", result.items().isEmpty());
    model.addAttribute("nextCursor", result.next().orElse(null));
    model.addAttribute("user", user);
    return "repositories/list";
  }

  /** Renders a repository detail view after the service performs current READ authorization. */
  @GetMapping("/repositories/{repositoryId}")
  public String detail(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable long repositoryId,
      Model model) {
    model.addAttribute("repository", service.detail(user, repositoryId));
    return "repositories/detail";
  }

  /** Registers a repository from a CSRF-protected form and redirects to its detail page. */
  @PostMapping("/repositories")
  public String create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @ModelAttribute DockerRepositoryRequest request) {
    return "redirect:/repositories/" + service.create(user, request).id();
  }
}
