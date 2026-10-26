package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** Authorized intersection of requested scope and current permissions. */
public record RegistryGrant(DockerRepositoryName repository, Set<RegistryAction> actions) {
  /** Copies caller collections; no I/O. */
  public RegistryGrant {
    actions = Set.copyOf(actions);
  }
}
