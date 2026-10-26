package com.grallum.application.dto;

import com.grallum.application.model.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

/** Validated external data contract; contains no persistence behavior. */
public record DockerRepositoryRequest(
    @NotBlank @Pattern(regexp = "[a-z0-9-]{1,64}") String registry,
    @NotBlank @Size(max = 255) String name) {}
