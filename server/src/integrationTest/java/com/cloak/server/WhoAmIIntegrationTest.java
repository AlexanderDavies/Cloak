package com.cloak.server;

import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Verifies JWKS resource-server auth against a real Keycloak and the standard {@link
 * com.cloak.server.common.web.WrappedResponse} envelope on success, on the security 401 path (which
 * proves the {@code CorrelationFilter} runs before the security entry point), and on a framework
 * 404 (which proves framework errors flow through the global advice).
 */
class WhoAmIIntegrationTest extends IntegrationTestBase {

  @LocalServerPort int port;

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @Test
  void validToken_returnsSubEnvelope() {
    String token = Tokens.accessToken(issuerUri(), "alice");
    client()
        .get()
        .uri("/v1/me")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .exists("X-Trace-Id")
        .expectBody()
        .jsonPath("$.data.sub")
        .exists()
        .jsonPath("$.errors")
        .doesNotExist()
        .jsonPath("$.traceId")
        .exists();
  }

  @Test
  void noToken_returns401Envelope() {
    client()
        .get()
        .uri("/v1/me")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED)
        .expectHeader()
        .exists("X-Trace-Id")
        .expectBody()
        .jsonPath("$.data")
        .doesNotExist()
        .jsonPath("$.errors[0].code")
        .isEqualTo("UNAUTHORIZED")
        .jsonPath("$.traceId")
        .exists();
  }

  @Test
  void authenticatedRequest_toUnknownPath_returns404Envelope() {
    String token = Tokens.accessToken(issuerUri(), "alice");
    client()
        .get()
        .uri("/v1/does-not-exist")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectHeader()
        .exists("X-Trace-Id")
        .expectBody()
        .jsonPath("$.data")
        .doesNotExist()
        .jsonPath("$.errors[0].code")
        .isEqualTo("NOT_FOUND")
        .jsonPath("$.traceId")
        .exists();
  }
}
