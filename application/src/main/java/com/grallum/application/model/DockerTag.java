package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** Observed current tag digest and conservative retention age. */
public record DockerTag(String tag, String digest, Instant firstSeen, boolean index) {}
