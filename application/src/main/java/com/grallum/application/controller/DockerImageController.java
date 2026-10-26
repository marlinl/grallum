package com.grallum.application.controller;

import com.grallum.application.dto.*;
import com.grallum.application.model.*;
import com.grallum.application.service.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/** Binds DockerImage HTTP inputs and delegates business decisions to its service. */
@Controller
public class DockerImageController {
  private final DockerImageService service;

  /** Retains the use-case service; construction performs no database or backend access. */
  public DockerImageController(DockerImageService service) {
    this.service = service;
  }

  /** Returns a typed local tag page under current READ authorization. */
  @GetMapping("/api/repositories/{repositoryId}/tags")
  @ResponseBody
  public PageResponse<DockerTagResponse> tags(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable long repositoryId,
      PageQuery page) {
    return service.tags(user, repositoryId, page);
  }

  /** Renders local tags with their observed timestamps and manifest digests. */
  @GetMapping("/repositories/{repositoryId}/tags")
  public String page(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable long repositoryId,
      PageQuery page,
      Model model) {
    var result = service.tags(user, repositoryId, page);
    model.addAttribute("tags", result);
    model.addAttribute("tagsEmpty", result.items().isEmpty());
    model.addAttribute("nextCursor", result.next().orElse(null));
    model.addAttribute("repositoryId", repositoryId);
    return "repositories/tags";
  }
}
