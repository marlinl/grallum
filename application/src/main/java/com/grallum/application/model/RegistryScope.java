package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** Requested repository token scope. */
public record RegistryScope(DockerRepositoryName repository, Set<RegistryAction> actions) {
  /** Copies caller collections; no I/O. */
  public RegistryScope {
    actions = Set.copyOf(actions);
  }
}
