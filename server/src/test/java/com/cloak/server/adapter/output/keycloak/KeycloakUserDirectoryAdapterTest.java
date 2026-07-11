package com.cloak.server.adapter.output.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.cloak.server.common.config.KeycloakAdminProperties;
import com.cloak.server.domain.identity.DirectoryUser;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Fast isolated unit tests for {@link KeycloakUserDirectoryAdapter}, exercising the token fetch +
 * exact-match search orchestration against a {@link MockRestServiceServer}-bound {@link RestClient}
 * — no running Keycloak. Enabled by constructor-injecting the {@code RestClient} (server/CLAUDE.md
 * §0.4). These cover the email-then-username priority that governs which user a handle resolves to.
 */
class KeycloakUserDirectoryAdapterTest {

  private static final String TOKEN_URI = "https://kc.test/token";
  private static final String USERS_URI = "https://kc.test/admin/users";
  private static final String TOKEN_JSON = "{\"access_token\":\"admin-tok\",\"expires_in\":300}";

  private KeycloakAdminProperties props() {
    return new KeycloakAdminProperties(TOKEN_URI, USERS_URI, "cloak-server-admin", "secret");
  }

  /** Binds a MockRestServiceServer to a fresh RestClient and builds the adapter around it. */
  private record Fixture(KeycloakUserDirectoryAdapter adapter, MockRestServiceServer server) {}

  private Fixture fixture() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    return new Fixture(new KeycloakUserDirectoryAdapter(props(), builder.build()), server);
  }

  @Test
  void findExact_emailMatch_returnsUser_withoutUsernameSearch() {
    Fixture f = fixture();
    f.server()
        .expect(requestTo(TOKEN_URI))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
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
    f.server()
        .expect(requestTo(TOKEN_URI))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
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
    f.server()
        .expect(requestTo(TOKEN_URI))
        .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
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
}
