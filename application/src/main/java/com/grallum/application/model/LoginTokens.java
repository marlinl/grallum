package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** Browser credentials handed only to the cookie-writing controller. */
public record LoginTokens(AccessToken accessToken, String refreshSecret, Instant refreshExpiresAt) {
  /** Returns a redacted description; pure. */
  @Override
  public String toString() {
    return "LoginTokens[REDACTED]";
  }
}
