package com.grallum.application.dto;

import com.grallum.application.model.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

/** Validated external data contract; contains no persistence behavior. */
public record UserCreateRequest(
    @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{1,64}") String username,
    @NotBlank @Size(min = 12, max = 72) String password,
    boolean admin) {
  /** Redacts credentials from diagnostics; pure. */
  @Override
  public String toString() {
    return "UserCreateRequest[REDACTED]";
  }
}
