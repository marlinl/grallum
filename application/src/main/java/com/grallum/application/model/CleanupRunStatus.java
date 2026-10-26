package com.grallum.application.model;

/** Durable cleanup lifecycle states. */
public enum CleanupRunStatus {
  PENDING,
  RUNNING,
  SUCCEEDED,
  PARTIAL,
  FAILED,
  SKIPPED
}
