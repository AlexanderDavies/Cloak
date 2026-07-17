package com.cloak.server.adapter.input.rest.users;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Tokens;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-transport coverage of the Keycloak Admin API failure paths (ARCHITECTURE_GUIDE §7.4, §12.4).
 * A {@code wiremock/wiremock} container stands in for the Admin API — the adapter's outbound calls
 * go over real HTTP through the {@code @Retry}/{@code @CircuitBreaker}-annotated client — while the
 * caller's bearer token is still validated by the real Keycloak container. It proves, on the wire,
 * that a 5xx maps to 503 {@code DEPENDENCY_UNAVAILABLE}, a definitive 4xx maps to 502 {@code
 * UPSTREAM_REJECTED}, and the upstream response body never leaks into the client response.
 *
 * <p>The Admin API URIs are overridden via a highest-precedence property source ({@link
 * WireMockAdminInitializer}, {@code addFirst}) rather than {@code @DynamicPropertySource}: the base
 * class also registers those keys, and a subclass {@code @DynamicPropertySource} is not guaranteed
 * to win the ordering.
 */
@ContextConfiguration(
    initializers = UserLookupResilienceIntegrationTest.WireMockAdminInitializer.class)
class UserLookupResilienceIntegrationTest extends IntegrationTestBase {

  // A recognisable upstream error body — it must never appear in what we return to the client.
  private static final String UPSTREAM_BODY = "SECRET-UPSTREAM-DIAGNOSTIC";

  static final GenericContainer<?> WIREMOCK =
      new GenericContainer<>(DockerImageName.parse("wiremock/wiremock:3.13.1"))
          .withExposedPorts(8080);

  static {
    WIREMOCK.start();
  }

  private static String wireMockUrl() {
    return "http://" + WIREMOCK.getHost() + ":" + WIREMOCK.getMappedPort(8080);
  }

  /** Points ONLY the Admin API at WireMock; the JWT issuer still resolves to the real Keycloak. */
  static class WireMockAdminInitializer
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "wiremock-keycloak-admin",
                  Map.of(
                      "cloak.keycloak-admin.token-uri", wireMockUrl() + "/token",
                      "cloak.keycloak-admin.users-uri", wireMockUrl() + "/admin/users")));
    }
  }

  @LocalServerPort int port;

  @Autowired CircuitBreakerRegistry circuitBreakers;

  private WireMock wireMock;

  @BeforeEach
  void resetState() {
    // Clear breaker state and stubs so each test starts from a clean, isolated slate.
    circuitBreakers.circuitBreaker("keycloak-admin").reset();
    wireMock = new WireMock(WIREMOCK.getHost(), WIREMOCK.getMappedPort(8080));
    wireMock.resetMappings();
    wireMock.register(
        post(urlPathEqualTo("/token"))
            .willReturn(okJson("{\"access_token\":\"admin-tok\",\"expires_in\":300}")));
  }

  private RestTestClient.ResponseSpec lookup() {
    String aliceToken = Tokens.accessToken(issuerUri(), "alice");
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .build()
        .get()
        .uri("/v1/users/lookup?handle={handle}", "bob@example.com")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
        .exchange();
  }

  @Test
  void keycloakServerError_returns503_dependencyUnavailable_withoutLeakingBody() {
    wireMock.register(
        get(urlPathEqualTo("/admin/users"))
            .willReturn(aResponse().withStatus(500).withBody(UPSTREAM_BODY)));

    String body =
        lookup()
            .expectStatus()
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    assertThat(body).contains("DEPENDENCY_UNAVAILABLE");
    assertThat(body).doesNotContain(UPSTREAM_BODY);
  }

  @Test
  void keycloakClientError_returns502_upstreamRejected_withoutLeakingBody() {
    wireMock.register(
        get(urlPathEqualTo("/admin/users"))
            .willReturn(aResponse().withStatus(400).withBody(UPSTREAM_BODY)));

    String body =
        lookup()
            .expectStatus()
            .isEqualTo(HttpStatus.BAD_GATEWAY)
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    assertThat(body).contains("UPSTREAM_REJECTED");
    assertThat(body).doesNotContain(UPSTREAM_BODY);
  }
}
