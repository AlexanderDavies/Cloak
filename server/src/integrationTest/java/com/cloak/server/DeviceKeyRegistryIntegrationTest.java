package com.cloak.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Tokens;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

class DeviceKeyRegistryIntegrationTest extends IntegrationTestBase {

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  /**
   * Generates a unique 33-byte public key (0x05 prefix + seed byte + zeros) encoded as base64. The
   * seed byte ensures distinctness across users so the UNIQUE(public_key) DB constraint is never
   * violated by test data.
   */
  private static String uniquePub(int seed) {
    byte[] b = new byte[33];
    b[0] = 0x05;
    b[1] = (byte) seed;
    return Base64.getEncoder().encodeToString(b);
  }

  /** Returns a unique 64-byte signature encoded as base64. */
  private static String uniqueSig(int seed) {
    byte[] b = new byte[64];
    b[0] = (byte) seed;
    return Base64.getEncoder().encodeToString(b);
  }

  /** Returns a unique 1569-byte ML-KEM-1024 public key (libsignal type tag included). */
  private static String uniqueKyberPub(int seed) {
    byte[] b = new byte[1569];
    b[0] = (byte) seed;
    return Base64.getEncoder().encodeToString(b);
  }

  private static String bundleJson(int seed) {
    return """
      { "registrationId": 12345, "deviceId": 1,
        "identityKey": "%s",
        "signedPreKey": { "keyId": 1, "publicKey": "%s", "signature": "%s" },
        "kyberPreKey": { "keyId": 1, "publicKey": "%s", "signature": "%s" },
        "oneTimePreKeys": [ { "keyId": 1, "publicKey": "%s" }, { "keyId": 2, "publicKey": "%s" } ] }
      """
        .formatted(
            uniquePub(seed),
            uniquePub(seed + 1),
            uniqueSig(seed),
            uniqueKyberPub(seed),
            uniqueSig(seed + 1),
            uniquePub(seed + 2),
            uniquePub(seed + 3));
  }

  @Test
  void publishesBundle_persistsDeviceAndPrekeys() {
    String token = Tokens.accessToken(issuerUri(), "alice");
    String sub = Tokens.subject(token);

    client()
        .put()
        .uri("/v1/keys")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .body(bundleJson(10))
        .exchange()
        .expectStatus()
        .isNoContent();

    Integer devices =
        jdbc.queryForObject(
            "select count(*) from device where owner_sub = ? and device_number = 1",
            Integer.class,
            sub);
    assertThat(devices).isEqualTo(1);
    Integer otps =
        jdbc.queryForObject(
            "select count(*) from one_time_prekey o join device d on o.device_id = d.id"
                + " where d.owner_sub = ?",
            Integer.class,
            sub);
    assertThat(otps).isEqualTo(2);
  }

  @Test
  void rePublish_isIdempotent_noDuplicateDevice() {
    String token = Tokens.accessToken(issuerUri(), "bob");
    String sub = Tokens.subject(token);
    for (int i = 0; i < 2; i++) {
      client()
          .put()
          .uri("/v1/keys")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .contentType(MediaType.APPLICATION_JSON)
          .body(bundleJson(20))
          .exchange()
          .expectStatus()
          .isNoContent();
    }
    Integer devices =
        jdbc.queryForObject("select count(*) from device where owner_sub = ?", Integer.class, sub);
    assertThat(devices).isEqualTo(1);
  }

  @Test
  void publishesBundle_persistsKyberPrekey() {
    String token = Tokens.accessToken(issuerUri(), "alice");
    String sub = Tokens.subject(token);

    client()
        .put()
        .uri("/v1/keys")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .body(bundleJson(10))
        .exchange()
        .expectStatus()
        .isNoContent();

    Integer kyberRows =
        jdbc.queryForObject(
            "select count(*) from kyber_prekey k join device d on k.device_id = d.id"
                + " where d.owner_sub = ?",
            Integer.class,
            sub);
    assertThat(kyberRows).isEqualTo(1);

    // Verify the decoded public key is 1569 bytes (1568 raw ML-KEM-1024 + 1 libsignal type tag).
    byte[] storedKey =
        jdbc.queryForObject(
            "select k.public_key from kyber_prekey k join device d on k.device_id = d.id"
                + " where d.owner_sub = ?",
            byte[].class,
            sub);
    assertThat(storedKey).hasSize(1569);
  }

  @Test
  void rePublish_replacesKyberPrekey_exactlyOneRow() {
    String token = Tokens.accessToken(issuerUri(), "bob");
    String sub = Tokens.subject(token);

    for (int i = 0; i < 2; i++) {
      client()
          .put()
          .uri("/v1/keys")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .contentType(MediaType.APPLICATION_JSON)
          .body(bundleJson(40))
          .exchange()
          .expectStatus()
          .isNoContent();
    }

    Integer kyberRows =
        jdbc.queryForObject(
            "select count(*) from kyber_prekey k join device d on k.device_id = d.id"
                + " where d.owner_sub = ?",
            Integer.class,
            sub);
    assertThat(kyberRows).isEqualTo(1);
  }

  @Test
  void malformedKyberKey_returns400() {
    String token = Tokens.accessToken(issuerUri(), "alice");
    // kyberPreKey publicKey is only 1 byte (wrong length — should be 1569)
    String badKyberJson =
        """
        { "registrationId": 12345, "deviceId": 1,
          "identityKey": "%s",
          "signedPreKey": { "keyId": 1, "publicKey": "%s", "signature": "%s" },
          "kyberPreKey": { "keyId": 1, "publicKey": "AA==", "signature": "%s" },
          "oneTimePreKeys": [ { "keyId": 1, "publicKey": "%s" } ] }
        """
            .formatted(uniquePub(50), uniquePub(51), uniqueSig(50), uniqueSig(51), uniquePub(52));
    client()
        .put()
        .uri("/v1/keys")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .body(badKyberJson)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void malformedBundle_returns400() {
    String token = Tokens.accessToken(issuerUri(), "alice");
    client()
        .put()
        .uri("/v1/keys")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .body("{ \"registrationId\": 1, \"deviceId\": 1, \"identityKey\": \"AA==\" }")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void noToken_returns401() {
    client()
        .put()
        .uri("/v1/keys")
        .contentType(MediaType.APPLICATION_JSON)
        .body(bundleJson(30))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
