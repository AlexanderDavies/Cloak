package com.cloak.server.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One distinct problem in a failed REST response. Carries a stable SCREAMING_SNAKE {@code code}, a
 * safe human-readable {@code message} (never a stack trace, SQL, ciphertext, or PII — root §0.6),
 * and an optional {@code field} for field-level errors. A {@code null} {@code field} is omitted
 * from the serialised JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, String field) {

  /** A non-field error: {@code code} + {@code message}, no field. */
  public static ApiError of(String code, String message) {
    return new ApiError(code, message, null);
  }

  /** A field-level error: {@code code} + {@code message} + the offending {@code field}. */
  public static ApiError field(String code, String message, String field) {
    return new ApiError(code, message, field);
  }
}
