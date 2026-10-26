package com.grallum.application.exception;

import com.grallum.application.config.WebProperties;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;

/**
 * Adapts business and input failures to safe responses without serializing implementation
 * exceptions.
 */
@ControllerAdvice
public class ManagementExceptionHandler {
  private final WebProperties web;

  /** Retains the trusted public origin used for registry authentication challenges. */
  public ManagementExceptionHandler(WebProperties web) {
    this.web = web;
  }

  /** Writes the business failure using the request's protocol and HEAD semantics. */
  @ExceptionHandler(BusinessException.class)
  public void business(
      BusinessException error, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    HttpErrorWriter.write(request, response, error, web.publicBaseUrl().toString());
  }

  /**
   * Maps binding and validation errors to a safe input failure without reflecting submitted
   * secrets.
   */
  @ExceptionHandler({
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    jakarta.validation.ConstraintViolationException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    IllegalArgumentException.class
  })
  public void invalid(Exception error, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    business(
        new BusinessException(BusinessErrorCode.INVALID_INPUT, "Invalid input"), request, response);
  }

  /** Maps uniqueness or integrity conflicts without exposing SQL or submitted values. */
  @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
  public void conflict(Exception error, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    business(
        new BusinessException(BusinessErrorCode.CONFLICT, "State conflict"), request, response);
  }

  /** Fails closed on unavailable persistence without misclassifying it as an incorrect password. */
  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  public void unavailable(Exception error, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    business(
        new BusinessException(BusinessErrorCode.UNAVAILABLE, "Persistence unavailable"),
        request,
        response);
  }

  /**
   * Maps an uncommitted streaming failure without claiming a partially sent response is complete.
   */
  @ExceptionHandler(IOException.class)
  public void transport(IOException error, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (response.isCommitted()) throw error;
    business(
        new BusinessException(
            error instanceof java.net.SocketTimeoutException
                ? BusinessErrorCode.TIMEOUT
                : BusinessErrorCode.DEPENDENCY_FAILURE,
            "Registry transport interrupted"),
        request,
        response);
  }
}
