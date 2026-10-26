package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** Verified identity plus claims; services recheck current account state. */
public record AuthenticatedUser(
    UUID id, String username, boolean administrator, JwtClaims claims) {}
