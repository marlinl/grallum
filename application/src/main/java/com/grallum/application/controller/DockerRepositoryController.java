package com.grallum.application.controller;

import com.grallum.application.dto.*;
import com.grallum.application.model.*;
import com.grallum.application.service.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

/** Binds DockerRepository HTTP inputs and delegates business decisions to its service. */
@Controller
public class DockerRepositoryController {
  private final DockerRepositoryService service;

  /** Retains the use-case service; construction performs no database or backend access. */
  public DockerRepositoryController(DockerRepositoryService service) {
    this.service = service;
  }

  /** Lists the current caller's visible repository page without exposing internal backend URLs. */
  @GetMapping("/api/repositories")
  @ResponseBody
  public PageResponse<DockerRepositoryResponse> list(
      @AuthenticationPrincipal AuthenticatedUser user, PageQuery page) {
    return service.list(user, page);
  }

  /** Registers a validated repository through the administrator use case. */
  @PostMapping("/api/repositories")
  @ResponseBody
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public DockerRepositoryResponse create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody DockerRepositoryRequest request) {
    return service.create(user, request);
  }
}
