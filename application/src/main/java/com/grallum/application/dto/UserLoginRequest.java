package com.grallum.application.dto;

import com.grallum.application.model.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

/** Validated external data contract; contains no persistence behavior. */
public record UserLoginRequest(
    @NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 256) String password) {
  /** Redacts credentials from diagnostics; pure. */
  @Override
  public String toString() {
    return "UserLoginRequest[REDACTED]";
  }
}
