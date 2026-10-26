package com.grallum.application.dto;

import com.grallum.application.model.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

/** Validated external data contract; contains no persistence behavior. */
public record UserUpdateRequest(
    boolean enabled, boolean admin, @Size(min = 12, max = 72) String password) {
  /** Redacts credentials from diagnostics; pure. */
  @Override
  public String toString() {
    return "UserUpdateRequest[REDACTED]";
  }
}
