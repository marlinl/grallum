package com.grallum.application.model;

import com.grallum.application.exception.*;
import java.time.*;
import java.util.*;

/** Bounded external pagination parameters. */
public record PageQuery(int size, Optional<String> after) {
  /** Validates page size and normalizes an absent cursor; no I/O. */
  public PageQuery {
    if (size < 1 || size > 1000)
      throw new BusinessException(BusinessErrorCode.INVALID_INPUT, "Invalid page size");
    after = after == null ? Optional.empty() : after;
  }
}
