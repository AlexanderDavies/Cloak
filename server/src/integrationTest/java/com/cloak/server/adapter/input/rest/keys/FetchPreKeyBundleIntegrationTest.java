package com.cloak.server.adapter.input.rest.keys;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Tokens;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

/**
 * Integration tests for {@code GET /v1/keys/{sub}} — prekey bundle fetch with atomic one-time
 * prekey consumption. Covers: happy-path OTP claim, sequential OTP uniqueness, concurrent OTP
 * uniqueness (SKIP LOCKED), OTP-exhausted device, unknown-sub 404, unauthenticated 401.
 */
class FetchPreKeyBundleIntegrationTest extends IntegrationTestBase {

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  // ── Key-material helpers (mirrors DeviceKeyRegistryIntegrationTest) ─────────────────────────

  /**
   * Generates a unique 33-byte Curve25519 public key (0x05 prefix + seed byte + zeros). The seed
   * byte ensures each registration uses a distinct identity key, avoiding the UNIQUE(public_key)
   * constraint on device.public_key.
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

  /**
   * Builds a PUT /v1/keys JSON body with two one-time prekeys (keyId 10 and 20). Callers must use
   * seeds ≥ 100, spacing by ≥ 4, to avoid colliding with seeds used by
   * DeviceKeyRegistryIntegrationTest (10, 20, 30, 40, 50).
   */
  private static String bundleJson(int seed) {
    return """
        { "registrationId": 12345, "deviceId": 1,
          "identityKey": "%s",
          "signedPreKey": { "keyId": 1, "publicKey": "%s", "signature": "%s" },
          "kyberPreKey":  { "keyId": 1, "publicKey": "%s", "signature": "%s" },
          "oneTimePreKeys": [
            { "keyId": 10, "publicKey": "%s" },
            { "keyId": 20, "publicKey": "%s" }
          ] }
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

  /** Registers a bundle for the authenticated user (PUT /v1/keys). */
  private void register(String token, int seed) {
    client()
        .put()
        .uri("/v1/keys")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .body(bundleJson(seed))
        .exchange()
        .expectStatus()
        .isNoContent();
  }

  /** Performs GET /v1/keys/{sub} with the given token and returns the ResponseSpec. */
  private ResponseSpec fetch(String token, String sub) {
    return client()
        .get()
        .uri("/v1/keys/{sub}", sub)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .exchange();
  }

  /**
   * Fetches and returns the OTP keyId from the JSON body. OTP keyIds in our test data are 10 and
   * 20.
   */
  private int extractOtpKeyId(String token, String sub) {
    String body =
        fetch(token, sub)
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    if (body.contains("\"keyId\":10")) {
      return 10;
    } else if (body.contains("\"keyId\":20")) {
      return 20;
    }
    throw new IllegalStateException("Unexpected OTP keyId in response body: " + body);
  }

  // ── Tests ─────────────────────────────────────────────────────────────────────────────────────

  /**
   * A first fetch returns all bundle fields (registrationId, deviceId, identityKey, signedPreKey,
   * oneTimePreKey, kyberPreKey). Exactly one one_time_prekey row is marked consumed; the
   * kyber_prekey row is unchanged (never consumed).
   */
  @Test
  void firstFetch_returns200_withAllFields_andConsumesExactlyOneOtp() {
    String aliceToken = Tokens.accessToken(issuerUri(), "alice");
    String aliceSub = Tokens.subject(aliceToken);
    // Seed 100: fresh upsert — exactly 2 unconsumed OTPs (keyId 10, 20).
    register(aliceToken, 100);

    String bobToken = Tokens.accessToken(issuerUri(), "bob");
    fetch(bobToken, aliceSub)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.registrationId")
        .isEqualTo(12345)
        .jsonPath("$.data.deviceId")
        .isEqualTo(1)
        .jsonPath("$.data.identityKey")
        .isNotEmpty()
        .jsonPath("$.data.signedPreKey.keyId")
        .isEqualTo(1)
        .jsonPath("$.data.signedPreKey.publicKey")
        .isNotEmpty()
        .jsonPath("$.data.signedPreKey.signature")
        .isNotEmpty()
        .jsonPath("$.data.oneTimePreKey.keyId")
        .isNotEmpty()
        .jsonPath("$.data.kyberPreKey.keyId")
        .isEqualTo(1)
        .jsonPath("$.data.kyberPreKey.publicKey")
        .isNotEmpty()
        .jsonPath("$.data.kyberPreKey.signature")
        .isNotEmpty();

    // Exactly one OTP row consumed.
    Integer consumed =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM one_time_prekey o
            JOIN device d ON o.device_id = d.id
            WHERE d.owner_sub = ? AND o.consumed_at IS NOT NULL
            """,
            Integer.class,
            aliceSub);
    assertThat(consumed).isEqualTo(1);

    // Kyber prekey row still present and unmodified (has no consumed_at column).
    Integer kyberRows =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM kyber_prekey k
            JOIN device d ON k.device_id = d.id
            WHERE d.owner_sub = ?
            """,
            Integer.class,
            aliceSub);
    assertThat(kyberRows).isEqualTo(1);
  }

  /**
   * Two sequential fetches return distinct OTP keyIds (the first is never returned again). After
   * two fetches both OTPs are consumed.
   */
  @Test
  void sequentialFetches_returnDistinctOtpKeyIds_andExhaustPool() {
    String aliceToken = Tokens.accessToken(issuerUri(), "alice");
    String aliceSub = Tokens.subject(aliceToken);
    // Seed 110: fresh registration, 2 OTPs (keyId 10 and 20).
    register(aliceToken, 110);

    String bobToken = Tokens.accessToken(issuerUri(), "bob");

    int first = extractOtpKeyId(bobToken, aliceSub);
    int second = extractOtpKeyId(bobToken, aliceSub);

    assertThat(List.of(first, second))
        .as("sequential fetches must return OTP keyIds 10 and 20 in some order")
        .containsExactlyInAnyOrder(10, 20);

    // Both OTPs consumed.
    Integer consumed =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM one_time_prekey o
            JOIN device d ON o.device_id = d.id
            WHERE d.owner_sub = ? AND o.consumed_at IS NOT NULL
            """,
            Integer.class,
            aliceSub);
    assertThat(consumed).isEqualTo(2);
  }

  /**
   * Two concurrent fetches against a device with 2 OTPs each receive a distinct keyId — the {@code
   * FOR UPDATE SKIP LOCKED} in the atomic OTP claim prevents both from claiming the same row.
   */
  @Test
  void concurrentFetches_returnDistinctOtpKeyIds() throws ExecutionException, InterruptedException {
    String aliceToken = Tokens.accessToken(issuerUri(), "alice");
    String aliceSub = Tokens.subject(aliceToken);
    // Seed 120: fresh registration, 2 OTPs.
    register(aliceToken, 120);

    String bobToken = Tokens.accessToken(issuerUri(), "bob");

    // Use a virtual-thread executor for the two concurrent fetches.
    try (var vt = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Integer> future1 =
          CompletableFuture.supplyAsync(() -> extractOtpKeyId(bobToken, aliceSub), vt);
      CompletableFuture<Integer> future2 =
          CompletableFuture.supplyAsync(() -> extractOtpKeyId(bobToken, aliceSub), vt);

      int keyId1 = future1.get();
      int keyId2 = future2.get();

      assertThat(keyId1).as("concurrent fetches must claim distinct OTPs").isNotEqualTo(keyId2);
    }
  }

  /**
   * A device with all OTPs consumed returns 200 with the bundle but no {@code oneTimePreKey} field
   * — kyberPreKey and signedPreKey are still present (valid no-OTP X3DH).
   */
  @Test
  void exhaustedDevice_returns200_withoutOneTimePreKey() {
    String aliceToken = Tokens.accessToken(issuerUri(), "alice");
    String aliceSub = Tokens.subject(aliceToken);
    // Seed 130: fresh registration, 2 OTPs.
    register(aliceToken, 130);

    String bobToken = Tokens.accessToken(issuerUri(), "bob");
    // Consume both OTPs.
    fetch(bobToken, aliceSub).expectStatus().isOk();
    fetch(bobToken, aliceSub).expectStatus().isOk();

    // Third fetch: pool exhausted — no oneTimePreKey in body.
    fetch(bobToken, aliceSub)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.signedPreKey.keyId")
        .isEqualTo(1)
        .jsonPath("$.data.kyberPreKey.keyId")
        .isEqualTo(1)
        .jsonPath("$.data.oneTimePreKey")
        .doesNotExist();
  }

  /** An unknown sub returns 404 with a NOT_FOUND error code. */
  @Test
  void unknownSub_returns404() {
    String bobToken = Tokens.accessToken(issuerUri(), "bob");
    fetch(bobToken, "totally-unknown-sub-that-has-no-device")
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND)
        .expectBody()
        .jsonPath("$.errors[0].code")
        .isEqualTo("NOT_FOUND");
  }

  /** An unauthenticated request returns 401. */
  @Test
  void unauthenticated_returns401() {
    client()
        .get()
        .uri("/v1/keys/{sub}", "any-sub")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
