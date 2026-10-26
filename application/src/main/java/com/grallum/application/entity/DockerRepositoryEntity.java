package com.grallum.application.entity;

import com.grallum.application.model.*;
import java.time.*;
import java.util.*;

/** Persistence representation; never an HTTP response. */
public record DockerRepositoryEntity(long id, String registry, String name) {}
