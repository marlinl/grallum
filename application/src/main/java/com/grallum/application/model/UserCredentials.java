package com.grallum.application.model;

import java.time.*;
import java.util.*;

/** Transient explicit login credentials; never persist or log. */
public record UserCredentials(String username, String password) {
  /** Returns a redacted description; pure. */
  @Override
  public String toString() {
    return "UserCredentials[REDACTED]";
  }
}
