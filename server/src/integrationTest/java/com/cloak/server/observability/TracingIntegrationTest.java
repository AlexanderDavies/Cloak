package com.cloak.server.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Telemetry;
import com.cloak.server.support.Tokens;
import java.net.URI;
import java.time.Duration;
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
 * Asserts the message round-trip produces one connected distributed trace exported over OTLP and
 * visible in Tempo: the manual {@code cloak.ws.ingest} WebSocket-ingest span, with the Kafka
 * producer/consumer hop on the same trace (context propagated through the delivery backbone). This
 * is the end-to-end "follow one message" capability from ARCHITECTURE_GUIDE §10.
 */
class TracingIntegrationTest extends IntegrationTestBase {

  @LocalServerPort int port;

  @Test
  void messageRoundTrip_producesConnectedTrace_withWsIngestAndKafkaSpans() throws Exception {
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

    WebSocketHttpHeaders aliceHeaders = new WebSocketHttpHeaders();
    aliceHeaders.setBearerAuth(Tokens.accessToken(issuerUri(), "alice"));
    WebSocketSession alice =
        new StandardWebSocketClient()
            .execute(
                new TextWebSocketHandler() {},
                aliceHeaders,
                URI.create("ws://localhost:" + port + "/ws"))
            .get();

    String b64 = Base64.getEncoder().encodeToString(new byte[] {4, 2, 4, 2});
    String frame =
        "{\"messageId\":\"33333333-3333-3333-3333-333333333333\","
            + "\"toSub\":\"%s\",\"deviceId\":null,\"ciphertext\":\"%s\"}";
    alice.sendMessage(new TextMessage(frame.formatted(bobSub, b64)));
    received.get(15, TimeUnit.SECONDS); // ensure the full path ran before asserting on the trace

    // The WS-ingest span must exist...
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofSeconds(1))
        .until(() -> Telemetry.firstTraceId("{ name = \"cloak.ws.ingest\" }") != null);

    // ...and the same trace must carry the Kafka hop (producer/consumer span over the outbound
    // topic).
    String traceId = Telemetry.firstTraceId("{ name = \"cloak.ws.ingest\" }");
    String traceJson = Telemetry.getTraceById(traceId).toString();
    assertThat(traceJson).contains("cloak.ws.ingest");
    assertThat(traceJson).contains("cloak.messages.outbound"); // Kafka span on the same trace

    alice.close();
    bob.close();
  }
}
