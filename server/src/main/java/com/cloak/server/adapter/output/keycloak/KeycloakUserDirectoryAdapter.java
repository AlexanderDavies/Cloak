package com.cloak.server.adapter.output.keycloak;

import com.cloak.server.common.config.KeycloakAdminProperties;
import com.cloak.server.domain.identity.DirectoryUser;
import com.cloak.server.port.output.identity.UserDirectoryPort;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Resolves exact-match user lookups via the Keycloak Admin REST API. Obtains an admin access token
 * via client-credentials grant and caches it until near expiry (30-second safety margin), then
 * queries by email and falls back to username if no email match is found.
 *
 * <p><strong>Privacy invariants (root CLAUDE.md §0.6):</strong> the handle, token value, and
 * resolved sub are never logged at any level. Only the URI path (not query params) may appear in
 * framework-level debug logs; callers must not add MDC entries for PII.
 */
@Component
public class KeycloakUserDirectoryAdapter implements UserDirectoryPort {

  /** Keycloak token endpoint response — only fields needed for caching. */
  record TokenResponse(
      @JsonProperty("access_token") String accessToken,
      @JsonProperty("expires_in") long expiresIn) {}

  /** Minimal user projection from the Keycloak Admin user-search response. */
  record KeycloakUser(@JsonProperty("id") String id) {}

  /** Immutable cached token with expiry. */
  private record CachedToken(String accessToken, Instant expiresAt) {
    /** Returns true when the token is still valid with a 30-second safety margin. */
    boolean isValid() {
      return Instant.now().plusSeconds(30).isBefore(expiresAt);
    }
  }

  private static final ParameterizedTypeReference<List<KeycloakUser>> USER_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  private final KeycloakAdminProperties props;
  private final RestClient restClient;
  private final AtomicReference<CachedToken> tokenRef = new AtomicReference<>();

  /**
   * Creates the adapter.
   *
   * @param props admin client configuration (token URI, users URI, client id/secret)
   * @param restClient the injected HTTP client (see {@code KeycloakAdminConfig}); constructor
   *     injection keeps the adapter unit-testable with a {@code MockRestServiceServer}-bound client
   */
  KeycloakUserDirectoryAdapter(KeycloakAdminProperties props, RestClient restClient) {
    this.props = props;
    this.restClient = restClient;
  }

  /**
   * Finds a user by exact email, falling back to exact username if no email match is found.
   *
   * @param handle an exact email address or username; never logged
   * @return the resolved user (carrying only {@code sub}), or empty if no exact match
   */
  @Override
  public Optional<DirectoryUser> findExact(String handle) {
    String adminToken = fetchAdminToken();
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
   * Returns a valid admin access token, fetching a fresh one when the cached copy has expired or is
   * within 30 seconds of expiry. Concurrent refreshes are safe: duplicate fetches are harmless and
   * the last write wins.
   */
  private String fetchAdminToken() {
    CachedToken current = tokenRef.get();
    if (current != null && current.isValid()) {
      return current.accessToken();
    }
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", props.clientId());
    form.add("client_secret", props.clientSecret());
    TokenResponse resp =
        restClient
            .post()
            .uri(props.tokenUri())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(TokenResponse.class);
    Instant expiresAt = Instant.now().plusSeconds(resp.expiresIn());
    CachedToken fresh = new CachedToken(resp.accessToken(), expiresAt);
    tokenRef.set(fresh);
    return fresh.accessToken();
  }

  /**
   * Queries the Keycloak Admin user-search endpoint with {@code paramName=value&exact=true}.
   * Returns an empty list when no match is found; propagates {@link
   * org.springframework.web.client.RestClientResponseException} on non-2xx responses.
   */
  private List<KeycloakUser> searchUsers(String paramName, String value, String adminToken) {
    URI uri =
        UriComponentsBuilder.fromUriString(props.usersUri())
            .queryParam(paramName, value)
            .queryParam("exact", "true")
            .encode()
            .build()
            .toUri();
    List<KeycloakUser> result =
        restClient
            .get()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
            .retrieve()
            .body(USER_LIST_TYPE);
    return result != null ? result : List.of();
  }
}
