package com.grallum.application.model;

/** Per-digest deletion intent and confirmation states. */
public enum CleanupItemStatus {
  PLANNED,
  INTENT,
  DELETED,
  ALREADY_ABSENT,
  UNCERTAIN
}
