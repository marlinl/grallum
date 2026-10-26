package com.grallum.application.model;

import com.grallum.application.exception.*;

/** Canonical external registry alias and repository path. */
public record DockerRepositoryName(String registry, String name) {
  /** Validates the alias/path pair; rejects traversal and noncanonical names; no I/O. */
  public DockerRepositoryName {
    if (registry == null
        || !registry.matches("[a-z0-9-]{1,64}")
        || name == null
        || name.length() > 255
        || name.contains("..")
        || !name.matches("[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)"))
      throw new BusinessException(BusinessErrorCode.INVALID_INPUT, "Invalid repository name");
  }

  /** Returns the public alias-qualified name; pure. */
  public String externalName() {
    return registry + "/" + name;
  }
}
