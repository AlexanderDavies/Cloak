package com.cloak.server.adapter.output.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.cloak.server.adapter.output.keycloak.KeycloakAdminClient.KeycloakUser;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Proves the {@code @Retry}/{@code @CircuitBreaker} annotations on {@link KeycloakAdminClient}
 * actually fire through a Spring proxy on Boot 4 and that the {@code keycloak-admin} instance's
 * exception routing and aspect order are correct (ARCHITECTURE_GUIDE §7.4): 5xx and 429 are
 * retried, other 4xx are not; a recorded failure trips the breaker while a 4xx does not; and with
 * the CircuitBreaker aspect placed outside Retry, a whole retry sequence records as ONE failure.
 * Each test runs a minimal isolated context (only the two resilience4j autoconfigurations + a
 * {@link MockRestServiceServer}-backed client, no Testcontainers) and tunes the instance to isolate
 * one concern. End-to-end behaviour against the real transport is covered by {@code
 * UserLookupResilienceIntegrationTest}.
 */
class KeycloakAdminClientResilienceTest {

  private static final URI SEARCH = URI.create("https://kc.test/admin/users?email=x&exact=true");
  private static final String SERVER_ERROR =
      "org.springframework.web.client.HttpServerErrorException";
  private static final String TOO_MANY =
      "org.springframework.web.client.HttpClientErrorException$TooManyRequests";

  // Instance-scoped property prefixes, single-sourced from the production instance name.
  private static final String RETRY =
      "resilience4j.retry.instances." + KeycloakAdminClient.INSTANCE + ".";
  private static final String BREAKER =
      "resilience4j.circuitbreaker.instances." + KeycloakAdminClient.INSTANCE + ".";

  private ApplicationContextRunner runner(String... properties) {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                RetryAutoConfiguration.class, CircuitBreakerAutoConfiguration.class))
        .withUserConfiguration(TestConfig.class)
        .withPropertyValues(properties);
  }

  // MARK: - retry routing (CB left at defaults: window/min-calls of 100 → never opens here)

  @Test
  void retriesOnServerError_thenSucceeds() {
    runner(
            RETRY + "max-attempts=3",
            RETRY + "wait-duration=1ms",
            RETRY + "retry-exceptions=" + SERVER_ERROR)
        .run(
            ctx -> {
              KeycloakAdminClient client = ctx.getBean(KeycloakAdminClient.class);
              MockRestServiceServer server = ctx.getBean(MockRestServiceServer.class);
              server.expect(requestTo(SEARCH)).andRespond(withServerError());
              server
                  .expect(requestTo(SEARCH))
                  .andRespond(withSuccess("[{\"id\":\"u\"}]", MediaType.APPLICATION_JSON));

              List<KeycloakUser> result = client.searchUsers(SEARCH, "tok");

              assertThat(result).extracting(KeycloakUser::id).containsExactly("u");
              server.verify(); // both expectations consumed → exactly one retry
            });
  }

  @Test
  void retriesOnTooManyRequests_thenSucceeds() {
    runner(
            RETRY + "max-attempts=3",
            RETRY + "wait-duration=1ms",
            RETRY + "retry-exceptions=" + TOO_MANY)
        .run(
            ctx -> {
              KeycloakAdminClient client = ctx.getBean(KeycloakAdminClient.class);
              MockRestServiceServer server = ctx.getBean(MockRestServiceServer.class);
              server.expect(requestTo(SEARCH)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
              server
                  .expect(requestTo(SEARCH))
                  .andRespond(withSuccess("[{\"id\":\"u\"}]", MediaType.APPLICATION_JSON));

              List<KeycloakUser> result = client.searchUsers(SEARCH, "tok");

              assertThat(result).extracting(KeycloakUser::id).containsExactly("u");
              server.verify();
            });
  }

  @Test
  void doesNotRetryOnOtherClientError() {
    runner(
            RETRY + "max-attempts=3",
            RETRY + "wait-duration=1ms",
            RETRY + "retry-exceptions=" + SERVER_ERROR)
        .run(
            ctx -> {
              KeycloakAdminClient client = ctx.getBean(KeycloakAdminClient.class);
              MockRestServiceServer server = ctx.getBean(MockRestServiceServer.class);
              // Only one 401 is scripted: a retry would demand a second call and fail verify().
              server.expect(requestTo(SEARCH)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

              assertThatThrownBy(() -> client.searchUsers(SEARCH, "tok"))
                  .isInstanceOf(HttpClientErrorException.class);
              server.verify();
            });
  }

  // MARK: - circuit-breaker routing (retry disabled with max-attempts=1 to isolate the breaker)

  @Test
  void recordedFailureOpensBreaker_thenFailsFast() {
    runner(
            RETRY + "max-attempts=1",
            BREAKER + "sliding-window-type=count_based",
            BREAKER + "sliding-window-size=1",
            BREAKER + "minimum-number-of-calls=1",
            BREAKER + "failure-rate-threshold=50",
            BREAKER + "wait-duration-in-open-state=1m",
            BREAKER + "record-exceptions=" + SERVER_ERROR)
        .run(
            ctx -> {
              KeycloakAdminClient client = ctx.getBean(KeycloakAdminClient.class);
              MockRestServiceServer server = ctx.getBean(MockRestServiceServer.class);
              // One recorded 5xx fills the window → breaker OPEN; the second call must not hit
              // HTTP.
              server.expect(requestTo(SEARCH)).andRespond(withServerError());

              assertThatThrownBy(() -> client.searchUsers(SEARCH, "tok"))
                  .isInstanceOf(HttpServerErrorException.class);
              assertThatThrownBy(() -> client.searchUsers(SEARCH, "tok"))
                  .isInstanceOf(CallNotPermittedException.class);
              server.verify(); // only the first (scripted) call reached HTTP
            });
  }

  @Test
  void clientErrorDoesNotOpenBreaker() {
    runner(
            RETRY + "max-attempts=1",
            BREAKER + "sliding-window-type=count_based",
            BREAKER + "sliding-window-size=1",
            BREAKER + "minimum-number-of-calls=1",
            BREAKER + "failure-rate-threshold=50",
            BREAKER + "record-exceptions=" + SERVER_ERROR)
        .run(
            ctx -> {
              KeycloakAdminClient client = ctx.getBean(KeycloakAdminClient.class);
              MockRestServiceServer server = ctx.getBean(MockRestServiceServer.class);
              // Two 401s: since 4xx is not a recorded failure, the breaker stays CLOSED and the
              // second call still reaches HTTP (both expectations must be consumed).
              server.expect(requestTo(SEARCH)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
              server.expect(requestTo(SEARCH)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

              assertThatThrownBy(() -> client.searchUsers(SEARCH, "tok"))
                  .isInstanceOf(HttpClientErrorException.class);
              assertThatThrownBy(() -> client.searchUsers(SEARCH, "tok"))
                  .isInstanceOf(HttpClientErrorException.class);
              server.verify();
            });
  }

  // MARK: - aspect order (CircuitBreaker outside Retry)

  @Test
  void circuitBreakerOuterOfRetry_recordsOneFailurePerLogicalCall() {
    runner(
            "resilience4j.retry.retry-aspect-order=2", // inner
            "resilience4j.circuitbreaker.circuit-breaker-aspect-order=1", // outer
            RETRY + "max-attempts=3",
            RETRY + "wait-duration=1ms",
            RETRY + "retry-exceptions=" + SERVER_ERROR,
            BREAKER + "sliding-window-type=count_based",
            BREAKER + "sliding-window-size=10",
            BREAKER + "minimum-number-of-calls=10", // high → stays CLOSED during this single call
            BREAKER + "record-exceptions=" + SERVER_ERROR)
        .run(
            ctx -> {
              KeycloakAdminClient client = ctx.getBean(KeycloakAdminClient.class);
              MockRestServiceServer server = ctx.getBean(MockRestServiceServer.class);
              // One logical call that exhausts three retry attempts.
              server.expect(requestTo(SEARCH)).andRespond(withServerError());
              server.expect(requestTo(SEARCH)).andRespond(withServerError());
              server.expect(requestTo(SEARCH)).andRespond(withServerError());

              assertThatThrownBy(() -> client.searchUsers(SEARCH, "tok"))
                  .isInstanceOf(HttpServerErrorException.class);
              server.verify(); // 3 HTTP attempts → retry ran

              // With the breaker OUTSIDE retry, the whole 3-attempt sequence records as ONE
              // failure, not three — so the tuned window/min-calls count logical lookups.
              long failed =
                  ctx.getBean(CircuitBreakerRegistry.class)
                      .circuitBreaker(KeycloakAdminClient.INSTANCE)
                      .getMetrics()
                      .getNumberOfFailedCalls();
              assertThat(failed).isEqualTo(1);
            });
  }

  @EnableAspectJAutoProxy(proxyTargetClass = true)
  @Configuration
  static class TestConfig {
    private final RestClient.Builder builder = RestClient.builder();

    @Bean
    MockRestServiceServer mockServer() {
      return MockRestServiceServer.bindTo(builder).build();
    }

    // Depends on the server bean so the builder is bound to the mock request factory before
    // build().
    @Bean
    RestClient keycloakAdminRestClient(MockRestServiceServer mockServer) {
      return builder.build();
    }

    @Bean
    KeycloakAdminClient keycloakAdminClient(RestClient keycloakAdminRestClient) {
      return new KeycloakAdminClient(keycloakAdminRestClient);
    }
  }
}
