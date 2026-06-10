package com.cloak.server.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Mints Keycloak access tokens for seeded users via the {@code cloak-test} direct-grant client. */
public final class Tokens {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Tokens() {}

  /**
   * Reads the {@code sub} claim out of an access token by base64url-decoding the JWT payload. Used
   * by tests that must address a recipient by their Keycloak subject without a directory lookup.
   */
  public static String subject(String accessToken) {
    try {
      String payload =
          new String(java.util.Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]));
      return MAPPER.readTree(payload).get("sub").asText();
    } catch (Exception e) {
      throw new IllegalStateException("decode sub failed", e);
    }
  }

  /** Mints an access token for a seeded user via the cloak-test client (password grant). */
  public static String accessToken(String issuerUri, String username) {
    try {
      String body =
          "grant_type=password&client_id=cloak-test"
              + "&username="
              + username
              + "&password=password&scope=openid";
      HttpResponse<String> resp =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(issuerUri + "/protocol/openid-connect/token"))
                      .header("Content-Type", "application/x-www-form-urlencoded")
                      .POST(HttpRequest.BodyPublishers.ofString(body))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      JsonNode json = MAPPER.readTree(resp.body());
      JsonNode token = json.get("access_token");
      if (token == null) {
        throw new IllegalStateException("no access_token in response: " + resp.body());
      }
      return token.asText();
    } catch (Exception e) {
      throw new IllegalStateException("token mint failed", e);
    }
  }
}
