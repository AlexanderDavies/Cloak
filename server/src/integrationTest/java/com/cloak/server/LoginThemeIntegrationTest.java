package com.cloak.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloak.server.support.IntegrationTestBase;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Proves the realm renders the branded Cloak login theme (not stock Keycloak). */
class LoginThemeIntegrationTest extends IntegrationTestBase {

  private static final Logger log = LoggerFactory.getLogger(LoginThemeIntegrationTest.class);

  @Test
  void loginPage_usesCloakTheme() throws Exception {
    String body = fetchLoginPage();
    // Our theme injects cloak.css via theme.properties styles= — only present when the cloak theme
    // is active (stock theme has no cloak.css).
    assertThat(body).contains("cloak.css");
    // Self-registration is enabled in D1; the register link is always rendered by the theme.
    assertThat(body.toLowerCase()).contains("register");
  }

  @Test
  void loginPage_rendersBrandLogo() throws Exception {
    String body = fetchLoginPage();
    // The brand wordmark must appear in the rendered DOM, not just be referenced by CSS that
    // targets selectors the stock template never emits. Our overridden template.ftl renders an
    // <img> pointing at the theme's logo.svg with a "Cloak" alt — that is what makes the page
    // recognisably branded.
    assertThat(body).contains("logo.svg");
    assertThat(body).containsIgnoringCase("alt=\"Cloak\"");
  }

  /** GETs the cloak-ios login page (real PKCE S256 challenge) and returns the rendered HTML. */
  private String fetchLoginPage() throws Exception {
    // Build a real PKCE code_challenge (cloak-ios requires S256).
    byte[] verifierBytes = new byte[32];
    new SecureRandom().nextBytes(verifierBytes);
    String codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
    byte[] challengeBytes =
        MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
    String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes);

    String redirectUri =
        URLEncoder.encode("com.cloak.app://oauth-callback", StandardCharsets.UTF_8);
    String loginUrl =
        issuerUri()
            + "/protocol/openid-connect/auth?client_id=cloak-ios&response_type=code"
            + "&scope=openid&redirect_uri="
            + redirectUri
            + "&code_challenge="
            + codeChallenge
            + "&code_challenge_method=S256";

    HttpResponse<String> resp =
        HttpClient.newBuilder()
            .followRedirects(Redirect.NEVER)
            .build()
            .send(
                HttpRequest.newBuilder(URI.create(loginUrl)).build(),
                HttpResponse.BodyHandlers.ofString());
    String body = resp.body();
    log.info("Login page HTTP status: {}", resp.statusCode());
    log.info(
        "Login page Location: {}", resp.headers().firstValue("location").orElse("(no redirect)"));
    log.info("Login page body length: {}", body.length());
    log.info(
        "Login page body (first 500 chars): {}", body.substring(0, Math.min(500, body.length())));
    return body;
  }
}
