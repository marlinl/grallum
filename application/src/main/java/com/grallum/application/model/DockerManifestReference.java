package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** Referenced platform or nested-index manifest. */
public record DockerManifestReference(String digest, String mediaType) {}
