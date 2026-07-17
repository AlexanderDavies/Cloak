package com.cloak.server.adapter.output.keycloak;

import com.cloak.server.adapter.output.keycloak.KeycloakAdminClient.KeycloakUser;
import com.cloak.server.adapter.output.keycloak.KeycloakAdminClient.TokenResponse;
import com.cloak.server.common.config.KeycloakAdminProperties;
import com.cloak.server.domain.identity.DirectoryUser;
import com.cloak.server.port.output.identity.UserDirectoryPort;
import com.cloak.server.usecase.DependencyUnavailableException;
import com.cloak.server.usecase.UpstreamRejectedException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Resolves exact-match user lookups via the Keycloak Admin REST API. Obtains an admin access token
 * via client-credentials grant and caches it until near expiry (30-second safety margin), then
 * queries by email and falls back to username if no email match is found. The resilient HTTP (retry
 * + circuit breaker) lives in {@link KeycloakAdminClient}; this adapter orchestrates and, at a
 * single boundary ({@link #mapErrors}), translates every transport failure into a safe domain
 * exception so that a raw upstream exception (whose message may embed the response body) never
 * escapes to the web layer or the logs.
 *
 * <p><strong>Privacy invariants (root CLAUDE.md §0.6):</strong> the handle, token value, and
 * resolved sub are never logged at any level, and the upstream response body never reaches the
 * client or the log sink — {@link #mapErrors} converts every upstream exception into a safe-message
 * domain exception before it can be logged. Only the URI path (not query params) may appear in
 * framework-level debug logs; callers must not add MDC entries for PII.
 */
@Component
public class KeycloakUserDirectoryAdapter implements UserDirectoryPort {

  private static final String UNAVAILABLE = "The user directory is temporarily unavailable.";
  private static final String REJECTED = "The user directory rejected the request.";

  /** Immutable cached token with expiry. */
  private record CachedToken(String accessToken, Instant expiresAt) {
    /** Returns true when the token is still valid with a 30-second safety margin. */
    boolean isValid() {
      return Instant.now().plusSeconds(30).isBefore(expiresAt);
    }
  }

  private final KeycloakAdminProperties props;
  private final KeycloakAdminClient client;
  private final AtomicReference<CachedToken> tokenRef = new AtomicReference<>();

  KeycloakUserDirectoryAdapter(KeycloakAdminProperties props, KeycloakAdminClient client) {
    this.props = props;
    this.client = client;
  }

  /**
   * Finds a user by exact email, falling back to exact username if no email match is found.
   *
   * @param handle an exact email address or username; never logged
   * @return the resolved user (carrying only {@code sub}), or empty if no exact match
   */
  @Override
  public Optional<DirectoryUser> findExact(String handle) {
    return mapErrors(
        () -> {
          try {
            return lookup(handle, fetchAdminToken(false));
          } catch (HttpClientErrorException.Unauthorized e) {
            // A still-unexpired cached token was rejected (Keycloak rotated keys / dropped the
            // service-account session). Discard it, obtain a fresh grant, and retry once so a stale
            // token self-heals instead of wedging every lookup until its clock expiry.
            return lookup(handle, fetchAdminToken(true));
          }
        });
  }

  private Optional<DirectoryUser> lookup(String handle, String adminToken) {
    List<KeycloakUser> byEmail = searchUsers("email", handle, adminToken);
    if (!byEmail.isEmpty()) {
      return Optional.of(new DirectoryUser(byEmail.get(0).id()));
    }
    List<KeycloakUser> byUsername = searchUsers("username", handle, adminToken);
    return byUsername.isEmpty()
        ? Optional.empty()
        : Optional.of(new DirectoryUser(byUsername.get(0).id()));
  }

  /**
   * Returns a valid admin access token. Uses the cached copy unless {@code forceRefresh} is set or
   * it has expired / is within 30 seconds of expiry. Concurrent refreshes are safe: duplicate
   * fetches are harmless and the last write wins.
   */
  private String fetchAdminToken(boolean forceRefresh) {
    if (!forceRefresh) {
      CachedToken current = tokenRef.get();
      if (current != null && current.isValid()) {
        return current.accessToken();
      }
    }
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", props.clientId());
    form.add("client_secret", props.clientSecret());
    TokenResponse resp = client.fetchToken(URI.create(props.tokenUri()), form);
    // A 200 with an empty/absent body yields a null token — the endpoint is misbehaving, so treat
    // it
    // as an unavailable dependency rather than dereferencing null.
    if (resp == null || resp.accessToken() == null) {
      throw new DependencyUnavailableException(UNAVAILABLE, null);
    }
    Instant expiresAt = Instant.now().plusSeconds(resp.expiresIn());
    CachedToken fresh = new CachedToken(resp.accessToken(), expiresAt);
    tokenRef.set(fresh);
    return fresh.accessToken();
  }

  /** Queries the Keycloak Admin user-search endpoint with {@code paramName=value&exact=true}. */
  private List<KeycloakUser> searchUsers(String paramName, String value, String adminToken) {
    URI uri =
        UriComponentsBuilder.fromUriString(props.usersUri())
            .queryParam(paramName, value)
            .queryParam("exact", "true")
            .encode()
            .build()
            .toUri();
    return client.searchUsers(uri, adminToken);
  }

  /**
   * The single boundary that maps a {@link KeycloakAdminClient} call's failures onto safe domain
   * exceptions, so no raw upstream exception (whose message may embed the response body, §0.6) ever
   * reaches the web layer or the logs:
   *
   * <ul>
   *   <li>retries exhausted on 5xx / timeouts / 429, an open breaker, or an unreadable body →
   *       {@link DependencyUnavailableException} (503, transient — retryable). A malformed body is
   *       a hard malfunction surfaced as 503; it is deliberately not retried (retrying will not fix
   *       it).
   *   <li>a definitive 4xx (other than 429) → {@link UpstreamRejectedException} (502) — our own
   *       request/credentials are wrong, not a transient outage; surfaced distinctly, never masked
   *       as a retryable 503.
   * </ul>
   */
  private <T> T mapErrors(Supplier<T> op) {
    try {
      return op.get();
    } catch (HttpClientErrorException.TooManyRequests e) {
      throw new DependencyUnavailableException(UNAVAILABLE, e);
    } catch (HttpClientErrorException e) {
      throw new UpstreamRejectedException(REJECTED, e);
    } catch (CallNotPermittedException | RestClientException | HttpMessageConversionException e) {
      throw new DependencyUnavailableException(UNAVAILABLE, e);
    }
  }
}
