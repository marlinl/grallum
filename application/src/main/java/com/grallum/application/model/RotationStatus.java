package com.grallum.application.model;

/** Refresh consumption results; replay revocation commits before failure delivery. */
public enum RotationStatus {
  ROTATED,
  REPLAY,
  EXPIRED,
  INVALID
}
