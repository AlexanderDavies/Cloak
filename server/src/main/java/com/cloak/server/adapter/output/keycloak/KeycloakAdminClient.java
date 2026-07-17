package com.cloak.server.adapter.output.keycloak;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.net.URI;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * The single resilience boundary for Keycloak Admin REST calls. Each method carries {@code @Retry}
 * and {@code @CircuitBreaker} for the {@code keycloak-admin} instance (configured under {@code
 * resilience4j.*} in application.yml, ARCHITECTURE_GUIDE §7.4); the aspects fire because callers
 * reach this bean through its Spring proxy. This type performs raw HTTP only — no token caching or
 * email-then-username orchestration (that is {@link KeycloakUserDirectoryAdapter}'s job) — and lets
 * transport failures propagate for the adapter to translate into a safe 503.
 *
 * <p>Retry re-runs the individual call, so both methods must stay idempotent: a token grant and an
 * exact user search both are.
 */
@Component
class KeycloakAdminClient {

  static final String INSTANCE = "keycloak-admin";

  /** Keycloak token endpoint response — only the fields needed for caching. */
  record TokenResponse(
      @JsonProperty("access_token") String accessToken,
      @JsonProperty("expires_in") long expiresIn) {}

  /** Minimal user projection from the Keycloak Admin user-search response. */
  record KeycloakUser(@JsonProperty("id") String id) {}

  private static final ParameterizedTypeReference<List<KeycloakUser>> USER_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  private final RestClient restClient;

  KeycloakAdminClient(RestClient keycloakAdminRestClient) {
    this.restClient = keycloakAdminRestClient;
  }

  @Retry(name = INSTANCE)
  @CircuitBreaker(name = INSTANCE)
  TokenResponse fetchToken(URI tokenUri, MultiValueMap<String, String> form) {
    return restClient
        .post()
        .uri(tokenUri)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(form)
        .retrieve()
        .body(TokenResponse.class);
  }

  @Retry(name = INSTANCE)
  @CircuitBreaker(name = INSTANCE)
  List<KeycloakUser> searchUsers(URI uri, String adminToken) {
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
