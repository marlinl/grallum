package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** Validated local manifest metadata. */
public record DockerManifest(
    String digest, String mediaType, boolean index, List<DockerManifestReference> references) {
  /** Copies caller collections; no I/O. */
  public DockerManifest {
    references = List.copyOf(references);
  }
}
