package com.cloak.server.adapter.input.rest.users;

import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Integration tests for {@code GET /v1/users/lookup?handle=...} — exact-match user lookup via the
 * Keycloak Admin REST API. Covers: lookup by email, lookup by username, non-existent handle → 404,
 * prefix/partial → 404 (proves exact-only, no enumeration), unauthenticated → 401.
 *
 * <p>Uses the seeded Keycloak realm (alice/bob) from {@link IntegrationTestBase}. Bob's stable sub
 * is {@code bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb} (fixed in {@code cloak-realm.json}).
 */
class UserLookupIntegrationTest extends IntegrationTestBase {

  /** Bob's Keycloak subject — stable, fixed in {@code iam/realm/cloak-realm.json}. */
  private static final String BOB_SUB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

  @LocalServerPort int port;

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  private RestTestClient.ResponseSpec lookup(String token, String handle) {
    return client()
        .get()
        .uri("/v1/users/lookup?handle={handle}", handle)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .exchange();
  }

  @Test
  void lookupByExactEmail_returns200_withSubAndDeviceId() {
    String aliceToken = Tokens.accessToken(issuerUri(), "alice");

    lookup(aliceToken, "bob@example.com")
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.sub")
        .isEqualTo(BOB_SUB)
        .jsonPath("$.data.deviceId")
        .isEqualTo(1)
        .jsonPath("$.errors")
        .doesNotExist()
        .jsonPath("$.traceId")
        .exists();
  }

  @Test
  void lookupByExactUsername_returns200_withCorrectSub() {
    String aliceToken = Tokens.accessToken(issuerUri(), "alice");

    lookup(aliceToken, "bob")
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.sub")
        .isEqualTo(BOB_SUB)
        .jsonPath("$.data.deviceId")
        .isEqualTo(1);
  }

  @Test
  void nonExistentHandle_returns404() {
    String aliceToken = Tokens.accessToken(issuerUri(), "alice");

    lookup(aliceToken, "nonexistent@example.com")
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND)
        .expectBody()
        .jsonPath("$.errors[0].code")
        .isEqualTo("NOT_FOUND");
  }

  @Test
  void partialHandlePrefix_returns404_provingExactMatchOnly() {
    // "bob@" is a prefix of "bob@example.com" — exact=true must prevent any match.
    String aliceToken = Tokens.accessToken(issuerUri(), "alice");

    lookup(aliceToken, "bob@")
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND)
        .expectBody()
        .jsonPath("$.errors[0].code")
        .isEqualTo("NOT_FOUND");
  }

  @Test
  void unauthenticated_returns401() {
    client()
        .get()
        .uri("/v1/users/lookup?handle=bob@example.com")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
