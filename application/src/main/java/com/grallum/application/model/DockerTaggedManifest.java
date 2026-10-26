package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** A tag and its completely validated local manifest. */
public record DockerTaggedManifest(String tag, DockerManifest manifest) {}
