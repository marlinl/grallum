package com.grallum.application.exception;

/** Stable business failure categories; transport mapping belongs at the application boundary. */
public enum BusinessErrorCode {
  INVALID_INPUT,
  UNAUTHENTICATED,
  FORBIDDEN,
  NOT_FOUND,
  CONFLICT,
  UNSUPPORTED,
  DEPENDENCY_FAILURE,
  TIMEOUT,
  UNAVAILABLE,
  CANCELLED,
  RATE_LIMITED
}
