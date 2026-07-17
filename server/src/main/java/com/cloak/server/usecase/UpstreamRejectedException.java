package com.cloak.server.usecase;

/**
 * A downstream dependency (e.g. the Keycloak Admin API) returned a definitive client-error (4xx
 * other than 429) — our own request/credentials are wrong, not a transient outage. Maps to HTTP 502
 * via {@code GlobalExceptionHandler}: the server, acting as a gateway to the dependency, received a
 * rejection it cannot fulfil. Distinct from {@link DependencyUnavailableException} (503, transient
 * and retryable). The message is a safe static string — never the upstream body, which may carry
 * PII (root CLAUDE.md §0.6).
 */
public class UpstreamRejectedException extends RuntimeException {

  /**
   * Wraps the underlying 4xx behind a safe, client-facing message.
   *
   * @param message a safe, static description — never the upstream response body
   * @param cause the underlying {@code HttpClientErrorException}
   */
  public UpstreamRejectedException(String message, Throwable cause) {
    super(message, cause);
  }
}
