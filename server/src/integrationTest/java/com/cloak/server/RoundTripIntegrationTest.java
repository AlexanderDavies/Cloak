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
 * unchanged to bob's live WebSocket session. Asserts the ciphertext is forwarded byte-for-byte
 * (same base64) and that no plaintext leaks into the delivered frame.
 */
class RoundTripIntegrationTest extends IntegrationTestBase {

  @LocalServerPort int port;

  @Test
  void aliceToBob_deliveredOverWebSocket_ciphertextUnchanged() throws Exception {
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
    WebSocketHttpHeaders aliceHeaders = new WebSocketHttpHeaders();
    aliceHeaders.setBearerAuth(Tokens.accessToken(issuerUri(), "alice"));
    WebSocketSession alice =
        new StandardWebSocketClient()
            .execute(
                new TextWebSocketHandler() {},
                aliceHeaders,
                URI.create("ws://localhost:" + port + "/ws"))
            .get();

    byte[] cipher = {9, 0, 2, 1, 0};
    String b64 = Base64.getEncoder().encodeToString(cipher);
    String frame =
        "{\"messageId\":\"77777777-7777-7777-7777-777777777777\","
            + "\"toSub\":\"%s\",\"deviceId\":null,\"ciphertext\":\"%s\"}";
    alice.sendMessage(new TextMessage(frame.formatted(bobSub, b64)));

    String delivered = received.get(15, TimeUnit.SECONDS);
    assertThat(delivered).contains(b64); // ciphertext forwarded unchanged
    assertThat(delivered).doesNotContain("plaintext");

    alice.close();
    bob.close();
  }
}
