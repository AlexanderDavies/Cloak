package com.cloak.server.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps everything that reaches the dispatcher onto the standard {@link WrappedResponse} envelope
 * (ARCHITECTURE_GUIDE §9.3, item 1). Each exception gets an explicit HTTP status and a stable
 * SCREAMING_SNAKE code; messages are sanitised, stable text — never a stack trace, SQL, ciphertext,
 * or PII (root §0.6).
 *
 * <p>Auth failures (401/403) are raised in the security filter chain before the dispatcher, so they
 * never reach this advice — {@link ApiAuthenticationEntryPoint} and {@link ApiAccessDeniedHandler}
 * emit the same envelope for those.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private static final String VALIDATION_ERROR = "VALIDATION_ERROR";

  /** Bean validation on a {@code @RequestBody} — one {@link ApiError} per offending field. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<WrappedResponse<Void>> onMethodArgumentNotValid(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    List<ApiError> errors = new ArrayList<>();
    for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
      errors.add(
          ApiError.field(VALIDATION_ERROR, fieldError.getDefaultMessage(), fieldError.getField()));
    }
    log.warn("Validation failed on {}: {} field error(s)", request.getRequestURI(), errors.size());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(WrappedResponse.error(errors));
  }

  /** Bean validation on method parameters (path/query/header) — one {@link ApiError} per result. */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<WrappedResponse<Void>> onHandlerMethodValidation(
      HandlerMethodValidationException ex, HttpServletRequest request) {
    List<ApiError> errors = new ArrayList<>();
    for (ParameterValidationResult result : ex.getParameterValidationResults()) {
      if (result instanceof ParameterErrors parameterErrors) {
        for (FieldError fieldError : parameterErrors.getFieldErrors()) {
          errors.add(
              ApiError.field(
                  VALIDATION_ERROR, fieldError.getDefaultMessage(), fieldError.getField()));
        }
      } else {
        String field = result.getMethodParameter().getParameterName();
        for (MessageSourceResolvable error : result.getResolvableErrors()) {
          errors.add(ApiError.field(VALIDATION_ERROR, error.getDefaultMessage(), field));
        }
      }
    }
    log.warn(
        "Validation failed on {}: {} parameter error(s)", request.getRequestURI(), errors.size());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(WrappedResponse.error(errors));
  }

  /** Unparseable request body — generic message, never the parser internals. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<WrappedResponse<Void>> onNotReadable(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    log.warn("Malformed request body on {}", request.getRequestURI());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            WrappedResponse.error(
                ApiError.of("MALFORMED_REQUEST", "The request body could not be read.")));
  }

  /** No handler / static resource matched the path. */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<WrappedResponse<Void>> onNoResource(
      NoResourceFoundException ex, HttpServletRequest request) {
    log.warn("No resource found for {}", request.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(
            WrappedResponse.error(
                ApiError.of("NOT_FOUND", "The requested resource was not found.")));
  }

  /** HTTP method not supported by the matched handler. */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<WrappedResponse<Void>> onMethodNotSupported(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
    log.warn("Method {} not allowed on {}", ex.getMethod(), request.getRequestURI());
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .body(
            WrappedResponse.error(
                ApiError.of(
                    "METHOD_NOT_ALLOWED", "The HTTP method is not allowed for this resource.")));
  }

  /** Request media type not supported by the matched handler. */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<WrappedResponse<Void>> onMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
    log.warn("Unsupported media type on {}", request.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .body(
            WrappedResponse.error(
                ApiError.of("UNSUPPORTED_MEDIA_TYPE", "The request media type is not supported.")));
  }

  /** Catch-all — never leak the cause; log the stack trace server-side only. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<WrappedResponse<Void>> onUnexpected(
      Exception ex, HttpServletRequest request) {
    log.error("Unexpected error on {}", request.getRequestURI(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            WrappedResponse.error(ApiError.of("INTERNAL_ERROR", "An unexpected error occurred.")));
  }
}
