package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** Committed refresh result; only ROTATED carries a usable replacement. */
public record RefreshRotation(RotationStatus status, Optional<RefreshSession> session) {}
