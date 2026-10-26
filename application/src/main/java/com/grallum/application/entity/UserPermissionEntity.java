package com.grallum.application.entity;

import com.grallum.application.model.*;
import java.time.*;
import java.util.*;

/** Persistence representation; never an HTTP response. */
public record UserPermissionEntity(
    long repositoryId, UUID userId, RepositoryPermission permission) {}
