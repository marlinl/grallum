package com.grallum.application.entity;

import com.grallum.application.model.*;
import java.time.*;
import java.util.*;

/** Persistence representation; never an HTTP response. */
public record DockerTagObservationEntity(
    long repositoryId, String tag, String digest, Instant firstSeen) {}
