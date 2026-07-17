package com.cloak.server.adapter.output.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.cloak.server.common.config.KeycloakAdminProperties;
import com.cloak.server.domain.identity.DirectoryUser;
import com.cloak.server.usecase.DependencyUnavailableException;
import com.cloak.server.usecase.UpstreamRejectedException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Fast isolated unit tests for {@link KeycloakUserDirectoryAdapter} against a {@link
 * MockRestServiceServer}-bound {@link RestClient} — no running Keycloak and, deliberately, no
 * Spring proxy, so the {@code @Retry}/{@code @CircuitBreaker} aspects on {@link
 * KeycloakAdminClient} do NOT fire here: every call is exactly one HTTP request. This covers the
 * adapter's own logic — the email-then-username exact-match priority, the stale-token self-heal
 * (401 → refresh + retry), and the transport-failure translation (5xx / 429 / unreadable body →
 * 503; definitive 4xx → 502). The aspect wiring itself is proven in {@code
 * KeycloakAdminClientResilienceTest} and end-to-end in {@code UserLookupResilienceIntegrationTest}.
 */
class KeycloakUserDirectoryAdapterTest {

  private static final String TOKEN_URI = "https://kc.test/token";
  private static final String USERS_URI = "https://kc.test/admin/users";
  private static final String TOKEN_JSON = "{\"access_token\":\"admin-tok\",\"expires_in\":300}";

  private KeycloakAdminProperties props() {
    return new KeycloakAdminProperties(TOKEN_URI, USERS_URI, "cloak-server-admin", "secret");
  }

  private record Fixture(KeycloakUserDirectoryAdapter adapter, MockRestServiceServer server) {}

  private Fixture fixture() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    KeycloakAdminClient client = new KeycloakAdminClient(builder.build());
    return new Fixture(new KeycloakUserDirectoryAdapter(props(), client), server);
  }

  private void expectToken(Fixture f) {
    f.server()
        .expect(requestTo(TOKEN_URI))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
  }

  private void expectEmailSearch(
      Fixture f, org.springframework.test.web.client.response.DefaultResponseCreator response) {
    f.server().expect(requestTo(startsWith(USERS_URI + "?email="))).andRespond(response);
  }

  // MARK: - exact-match orchestration

  @Test
  void findExact_emailMatch_returnsUser_withoutUsernameSearch() {
    Fixture f = fixture();
    expectToken(f);
    // Only the email search is expected — an email hit must NOT fall through to a username search.
    f.server()
        .expect(requestTo(startsWith(USERS_URI + "?email=")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[{\"id\":\"user-A\"}]", MediaType.APPLICATION_JSON));

    Optional<DirectoryUser> result = f.adapter().findExact("bob@example.com");

    assertThat(result).contains(new DirectoryUser("user-A"));
    f.server().verify();
  }

  @Test
  void findExact_noEmailMatch_fallsBackToUsername() {
    Fixture f = fixture();
    expectToken(f);
    f.server()
        .expect(requestTo(startsWith(USERS_URI + "?email=")))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    f.server()
        .expect(requestTo(startsWith(USERS_URI + "?username=")))
        .andRespond(withSuccess("[{\"id\":\"user-B\"}]", MediaType.APPLICATION_JSON));

    Optional<DirectoryUser> result = f.adapter().findExact("bob");

    assertThat(result).contains(new DirectoryUser("user-B"));
    f.server().verify();
  }

  @Test
  void findExact_noMatchOnEither_returnsEmpty() {
    Fixture f = fixture();
    expectToken(f);
    f.server()
        .expect(requestTo(startsWith(USERS_URI + "?email=")))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    f.server()
        .expect(requestTo(startsWith(USERS_URI + "?username=")))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    Optional<DirectoryUser> result = f.adapter().findExact("ghost");

    assertThat(result).isEmpty();
    f.server().verify();
  }

  // MARK: - stale-token self-heal

  @Test
  void findExact_staleToken401_refreshesTokenAndRetriesOnce() {
    Fixture f = fixture();
    // First token, then a 401 on search (cached token rejected mid-life), then a fresh token and a
    // successful retry. All four requests must be consumed.
    expectToken(f);
    expectEmailSearch(f, withStatus(HttpStatus.UNAUTHORIZED));
    expectToken(f);
    expectEmailSearch(f, withSuccess("[{\"id\":\"user-A\"}]", MediaType.APPLICATION_JSON));

    Optional<DirectoryUser> result = f.adapter().findExact("bob@example.com");

    assertThat(result).contains(new DirectoryUser("user-A"));
    f.server().verify();
  }

  @Test
  void findExact_persistentUnauthorized_mapsToUpstreamRejected() {
    Fixture f = fixture();
    // 401 survives the one refresh+retry → definitive rejection → 502, not an infinite loop.
    expectToken(f);
    expectEmailSearch(f, withStatus(HttpStatus.UNAUTHORIZED));
    expectToken(f);
    expectEmailSearch(f, withStatus(HttpStatus.UNAUTHORIZED));

    assertThatThrownBy(() -> f.adapter().findExact("bob@example.com"))
        .isInstanceOf(UpstreamRejectedException.class);
    f.server().verify();
  }

  // MARK: - transport-failure translation (§7.4)

  @Test
  void findExact_serverError_mapsToDependencyUnavailable() {
    Fixture f = fixture();
    expectToken(f);
    expectEmailSearch(f, withServerError());

    assertThatThrownBy(() -> f.adapter().findExact("bob@example.com"))
        .isInstanceOf(DependencyUnavailableException.class);
    f.server().verify();
  }

  @Test
  void findExact_tooManyRequests_mapsToDependencyUnavailable() {
    Fixture f = fixture();
    expectToken(f);
    // 429 is transient (rate-limited): it must surface as a retryable 503, not a 4xx/502.
    expectEmailSearch(f, withStatus(HttpStatus.TOO_MANY_REQUESTS));

    assertThatThrownBy(() -> f.adapter().findExact("bob@example.com"))
        .isInstanceOf(DependencyUnavailableException.class);
    f.server().verify();
  }

  @Test
  void findExact_definitiveClientError_mapsToUpstreamRejected() {
    Fixture f = fixture();
    expectToken(f);
    // A 400 is our own bad request — a definitive answer surfaced distinctly as 502, never masked
    // as a transient 503, and (unlike 401) it does not trigger a token refresh+retry.
    expectEmailSearch(f, withStatus(HttpStatus.BAD_REQUEST));

    assertThatThrownBy(() -> f.adapter().findExact("bob@example.com"))
        .isInstanceOf(UpstreamRejectedException.class);
    f.server().verify();
  }

  @Test
  void findExact_unreadableBody_mapsToDependencyUnavailable() {
    Fixture f = fixture();
    expectToken(f);
    // 200 but a body that cannot be deserialized to the expected type → dependency malfunction.
    expectEmailSearch(f, withSuccess("not-json", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> f.adapter().findExact("bob@example.com"))
        .isInstanceOf(DependencyUnavailableException.class);
    f.server().verify();
  }

  @Test
  void findExact_tokenEndpointReturnsEmptyBody_mapsToDependencyUnavailable() {
    Fixture f = fixture();
    // 200 with an empty body → null TokenResponse; must not NPE, must surface as unavailable.
    f.server().expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.OK));

    assertThatThrownBy(() -> f.adapter().findExact("bob@example.com"))
        .isInstanceOf(DependencyUnavailableException.class);
    f.server().verify();
  }
}
