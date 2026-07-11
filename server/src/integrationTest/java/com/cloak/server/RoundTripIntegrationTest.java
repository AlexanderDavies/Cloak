package com.cloak.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Tokens;
import java.net.URI;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The walking skeleton's keystone: an authenticated alice→bob round trip. Alice's opaque ciphertext
 * envelope is persisted, published to Kafka keyed by bob's {@code sub}, consumed, and delivered
 * unchanged to bob's live WebSocket session. Asserts the delivery frame carries the correct integer
 * device numbers, message type, sender identity, and byte-identical ciphertext — and that no
 * plaintext leaks into the delivered frame.
 */
class RoundTripIntegrationTest extends IntegrationTestBase {

  @LocalServerPort int port;

  @Test
  void aliceToBob_deliveredOverWebSocket_envelopeFieldsCorrect() throws Exception {
    // Bob connects and completes the future when a frame arrives.
    CompletableFuture<String> received = new CompletableFuture<>();
    String bobToken = Tokens.accessToken(issuerUri(), "bob");
    WebSocketHttpHeaders bobHeaders = new WebSocketHttpHeaders();
    bobHeaders.setBearerAuth(bobToken);
    final WebSocketSession bob =
        new StandardWebSocketClient()
            .execute(
                new TextWebSocketHandler() {
                  @Override
                  protected void handleTextMessage(WebSocketSession s, TextMessage m) {
                    received.complete(m.getPayload());
                  }
                },
                bobHeaders,
                URI.create("ws://localhost:" + port + "/ws"))
            .get();

    // Recipient is addressed by bob's Keycloak subject (no directory lookup in Phase 0).
    String bobSub = Tokens.subject(bobToken);

    // Alice connects and sends to bob.
    String aliceToken = Tokens.accessToken(issuerUri(), "alice");
    WebSocketHttpHeaders aliceHeaders = new WebSocketHttpHeaders();
    aliceHeaders.setBearerAuth(aliceToken);
    WebSocketSession alice =
        new StandardWebSocketClient()
            .execute(
                new TextWebSocketHandler() {},
                aliceHeaders,
                URI.create("ws://localhost:" + port + "/ws"))
            .get();

    byte[] cipher = {9, 0, 2, 1, 0};
    String b64 = Base64.getEncoder().encodeToString(cipher);
    // Inbound frame: integer device numbers + messageType (3 = PreKeySignalMessage).
    // No fromSub — sender is the authenticated principal, never a client-supplied field.
    String frame =
        "{\"messageId\":\"77777777-7777-7777-7777-777777777777\","
            + "\"toSub\":\"%s\",\"toDeviceId\":1,\"fromDeviceId\":1,"
            + "\"messageType\":3,\"ciphertext\":\"%s\"}";
    alice.sendMessage(new TextMessage(frame.formatted(bobSub, b64)));

    String delivered = received.get(15, TimeUnit.SECONDS);
    // Extract alice's sub just before asserting it is present — keeps distance ≤ 3 (Checkstyle).
    String aliceSub = Tokens.subject(aliceToken);
    // Ciphertext forwarded byte-for-byte (same base64).
    assertThat(delivered).contains(b64);
    // Sender identity set from JWT principal, not client frame.
    assertThat(delivered).contains(aliceSub);
    // Integer device numbers and message type preserved end-to-end.
    assertThat(delivered).contains("\"toDeviceId\":1");
    assertThat(delivered).contains("\"fromDeviceId\":1");
    assertThat(delivered).contains("\"messageType\":3");
    // Privacy: no plaintext in the delivery frame.
    assertThat(delivered).doesNotContain("plaintext");

    alice.close();
    bob.close();
  }
}
