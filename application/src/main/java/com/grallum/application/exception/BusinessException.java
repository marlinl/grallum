package com.grallum.application.exception;

/** Safe typed failure without transport, database or credential details. */
public class BusinessException extends RuntimeException {
  private final BusinessErrorCode code;

  /** Retains a safe public message and stable code; no external effects. */
  public BusinessException(BusinessErrorCode code, String safeMessage) {
    super(safeMessage);
    this.code = code;
  }

  /** Returns the stable category; pure. */
  public BusinessErrorCode code() {
    return code;
  }
}
