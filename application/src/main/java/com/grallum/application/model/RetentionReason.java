package com.grallum.application.model;

/** Reasons why a digest is retained by cleanup. */
public enum RetentionReason {
  RECENT_COUNT,
  AGE,
  PATTERN,
  SHARED_DIGEST,
  INDEX_DEPENDENCY
}
