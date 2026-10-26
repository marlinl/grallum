package com.grallum.application.model;

/** Confirmed local deletion outcomes; transport failures are not success. */
public enum DockerDeleteResult {
  DELETED,
  ALREADY_ABSENT
}
