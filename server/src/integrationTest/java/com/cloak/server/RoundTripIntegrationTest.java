package com.cloak.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.cloak.server.domain.message.ConversationId;
import com.cloak.server.domain.message.MessageId;
import com.cloak.server.port.output.message.MessageRepositoryPort;
import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Tokens;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
  @Autowired MessageRepositoryPort repository;

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
    String aliceSub = Tokens.subject(aliceToken);
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
    String conversationId = ConversationId.canonical(aliceSub, bobSub);
    // Inbound frame: integer device numbers + messageType (3 = PreKeySignalMessage).
    // No fromSub — sender is the authenticated principal, never a client-supplied field.
    String frame =
        "{\"messageId\":\"77777777-7777-7777-7777-777777777777\","
            + "\"toSub\":\"%s\",\"toDeviceId\":1,\"fromDeviceId\":1,"
            + "\"messageType\":3,\"conversationId\":\"%s\",\"ciphertext\":\"%s\"}";
    alice.sendMessage(new TextMessage(frame.formatted(bobSub, conversationId, b64)));

    String delivered = received.get(15, TimeUnit.SECONDS);
    // Ciphertext forwarded byte-for-byte (same base64).
    assertThat(delivered).contains(b64);
    // Sender identity set from JWT principal, not client frame.
    assertThat(delivered).contains(aliceSub);
    // Integer device numbers and message type preserved end-to-end.
    assertThat(delivered).contains("\"toDeviceId\":1");
    assertThat(delivered).contains("\"fromDeviceId\":1");
    assertThat(delivered).contains("\"messageType\":3");
    // conversationId forwarded unchanged from the validated inbound frame.
    assertThat(delivered).contains("\"conversationId\":\"" + conversationId + "\"");
    // Privacy: no plaintext in the delivery frame.
    assertThat(delivered).doesNotContain("plaintext");

    alice.close();
    bob.close();
  }

  @Test
  void mismatchedConversationId_frameIsDropped_noDeliveryAndNoPersistence() throws Exception {
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
    String bobSub = Tokens.subject(bobToken);

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

    String id = "88888888-8888-8888-8888-888888888888";
    byte[] cipher = {4, 5, 6};
    String b64 = Base64.getEncoder().encodeToString(cipher);
    // Valid-shaped hex, but not ConversationId.canonical(aliceSub, bobSub) — must be rejected.
    String wrongConversationId = "0".repeat(64);
    String frame =
        "{\"messageId\":\"%s\",\"toSub\":\"%s\",\"toDeviceId\":1,\"fromDeviceId\":1,"
            + "\"messageType\":3,\"conversationId\":\"%s\",\"ciphertext\":\"%s\"}";
    alice.sendMessage(new TextMessage(frame.formatted(id, bobSub, wrongConversationId, b64)));

    // Bounded wait for absence: the delivery future must stay incomplete for the whole window,
    // not just at a single poll (during(), not a bare sleep).
    await()
        .pollDelay(Duration.ofMillis(200))
        .during(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(5))
        .until(() -> !received.isDone());
    assertThat(repository.find(new MessageId(id))).isEmpty();

    alice.close();
    bob.close();
  }

  /**
   * {@code messageType} is only ever 2 (SignalMessage) or 3 (PreKeySignalMessage). An out-of-range
   * value must be rejected at ingest: the column is a SMALLINT (so a wide int would silently
   * narrow) and the recipient decodes it into a closed enum — a value it cannot map fails the
   * decode of the whole backlog page, wedging that device's history sync.
   */
  @Test
  void unsupportedMessageType_frameIsDropped_noDeliveryAndNoPersistence() throws Exception {
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
    String bobSub = Tokens.subject(bobToken);

    String aliceToken = Tokens.accessToken(issuerUri(), "alice");
    String aliceSub = Tokens.subject(aliceToken);
    WebSocketHttpHeaders aliceHeaders = new WebSocketHttpHeaders();
    aliceHeaders.setBearerAuth(aliceToken);
    WebSocketSession alice =
        new StandardWebSocketClient()
            .execute(
                new TextWebSocketHandler() {},
                aliceHeaders,
                URI.create("ws://localhost:" + port + "/ws"))
            .get();

    String id = "99999999-9999-9999-9999-999999999999";
    String b64 = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
    String conversationId = ConversationId.canonical(aliceSub, bobSub);
    // 65538 also proves the SMALLINT cast is not what rejects it: it narrows to 2, a valid type.
    String frame =
        "{\"messageId\":\"%s\",\"toSub\":\"%s\",\"toDeviceId\":1,\"fromDeviceId\":1,"
            + "\"messageType\":65538,\"conversationId\":\"%s\",\"ciphertext\":\"%s\"}";
    alice.sendMessage(new TextMessage(frame.formatted(id, bobSub, conversationId, b64)));

    await()
        .pollDelay(Duration.ofMillis(200))
        .during(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(5))
        .until(() -> !received.isDone());
    assertThat(repository.find(new MessageId(id))).isEmpty();

    alice.close();
    bob.close();
  }
}
