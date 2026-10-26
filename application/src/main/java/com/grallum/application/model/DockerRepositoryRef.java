package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** Registered repository identity and trusted deployment mode. */
public record DockerRepositoryRef(long id, DockerRepositoryName name, DockerRegistryMode mode) {}
