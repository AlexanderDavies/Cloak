package com.cloak.server.usecase;

/**
 * A downstream dependency (e.g. the Keycloak Admin API) could not be reached after retries, or its
 * circuit breaker is open. Maps to HTTP 503 via {@code GlobalExceptionHandler}. The message is a
 * safe static string — never the upstream body, which may carry PII (root CLAUDE.md §0.6).
 */
public class DependencyUnavailableException extends RuntimeException {

  /**
   * Wraps the underlying failure behind a safe, client-facing message.
   *
   * @param message a safe, static description — never the upstream response body
   * @param cause the underlying transport failure or {@code CallNotPermittedException}
   */
  public DependencyUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
