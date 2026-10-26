package com.grallum.application.dto;

import java.time.*;
import java.util.*;

/** Immutable visible page with an optional opaque next cursor. */
public record PageResponse<T>(List<T> items, Optional<String> next) {
  /** Copies the visible items; no I/O. */
  public PageResponse {
    items = List.copyOf(items);
    next = Objects.requireNonNull(next);
  }
}
