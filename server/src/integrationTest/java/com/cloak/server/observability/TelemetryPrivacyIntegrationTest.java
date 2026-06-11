package com.cloak.server.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Telemetry;
import com.cloak.server.support.Tokens;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
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
 * The privacy guard for telemetry (root CLAUDE.md §1/§2/§6, ARCHITECTURE_GUIDE §0.6.4): no
 * ciphertext, message body, or PII may appear in any exported signal, and the recipient {@code sub}
 * is never a metric label. Routes a message carrying a recognisable ciphertext sentinel, then
 * asserts the sentinel is absent from Loki logs and the trace's spans, and that no metric carries a
 * {@code sub} label. If this fails, an instrumentation point is leaking content — fix the leak,
 * never the test.
 */
class TelemetryPrivacyIntegrationTest extends IntegrationTestBase {

  @LocalServerPort int port;

  // Base64 of a unique marker; this is exactly what travels in the WS frame as the "ciphertext".
  private static final String SENTINEL =
      Base64.getEncoder()
          .encodeToString("CIPHERTEXT-SENTINEL-7Z9Q".getBytes(StandardCharsets.UTF_8));

  @Test
  void noCiphertextOrSub_leaksIntoMetricsTracesOrLogs() throws Exception {
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

    String frame =
        "{\"messageId\":\"55555555-5555-5555-5555-555555555555\","
            + "\"toSub\":\"%s\",\"deviceId\":null,\"ciphertext\":\"%s\"}";
    alice.sendMessage(new TextMessage(frame.formatted(bobSub, SENTINEL)));
    received.get(15, TimeUnit.SECONDS); // full path ran (persist → publish → consume → deliver)
    alice.close();
    bob.close();

    // Wait until the message's trace has been exported, so all signals for it have flushed.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofSeconds(1))
        .until(() -> Telemetry.firstTraceId("{ name = \"cloak.ws.ingest\" }") != null);

    // Prove the Loki pipeline is actually ingesting this service's logs first, so that an empty
    // sentinel query means "the ciphertext is absent", not "Loki silently dropped everything".
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofSeconds(1))
        .until(() -> !Telemetry.logsContaining("{service_name=\"cloak-server\"}").isEmpty());
    // No log line anywhere carries the ciphertext (server-side substring filter over all logs).
    assertThat(Telemetry.logsContaining("{service_name=\"cloak-server\"} |= \"" + SENTINEL + "\""))
        .isEmpty();

    // No span in the message trace carries the ciphertext.
    String traceJson =
        Telemetry.getTraceById(Telemetry.firstTraceId("{ name = \"cloak.ws.ingest\" }")).toString();
    assertThat(traceJson).doesNotContain(SENTINEL);

    // No app metric may carry a PII / high-cardinality identity label (e.g. a recipient sub).
    // Inspect the label names on the app's own cloak_* series and assert none look like PII.
    // Catches any future PII label whatever its name — not just a couple of fixed names.
    List<String> cloakMetricLabels = new ArrayList<>();
    Telemetry.promLabelNames("{__name__=~\"cloak_.*\"}")
        .forEach(n -> cloakMetricLabels.add(n.asString()));
    assertThat(cloakMetricLabels).as("inspected real cloak_* metric labels").contains("__name__");
    assertThat(cloakMetricLabels)
        .as("no PII/identity label on app metrics")
        .noneMatch(
            name ->
                name.toLowerCase(Locale.ROOT)
                    .matches(".*(sub|user|email|recipient|sender|ciphertext|device|phone).*"));
  }
}
