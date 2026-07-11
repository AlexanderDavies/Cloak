package com.cloak.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.cloak.server.domain.message.MessageId;
import com.cloak.server.port.output.message.MessageRepositoryPort;
import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Tokens;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

class WebSocketIngestIntegrationTest extends IntegrationTestBase {

  @LocalServerPort int port;
  @Autowired MessageRepositoryPort repository;

  @Test
  void authenticatedClient_sendsEnvelope_serverPersists() throws Exception {
    String token = Tokens.accessToken(issuerUri(), "alice");
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.setBearerAuth(token);

    WebSocketSession session =
        new StandardWebSocketClient()
            .execute(
                new TextWebSocketHandler() {},
                headers,
                URI.create("ws://localhost:" + port + "/ws"))
            .get();

    String id = "66666666-6666-6666-6666-666666666666";
    String ciphertext = Base64.getEncoder().encodeToString(new byte[] {5, 5, 5});
    session.sendMessage(
        new TextMessage(
            ("{\"messageId\":\"%s\",\"toSub\":\"bob-sub\","
                    + "\"toDeviceId\":1,\"fromDeviceId\":1,"
                    + "\"messageType\":3,\"ciphertext\":\"%s\"}")
                .formatted(id, ciphertext)));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(repository.find(new MessageId(id))).isPresent());
    session.close();
  }

  @Test
  void unauthenticatedClient_isRejected() {
    // No bearer header: the resource-server filter chain must reject the HTTP upgrade, so the
    // handshake future completes exceptionally.
    assertThatThrownBy(
            () ->
                new StandardWebSocketClient()
                    .execute(
                        new TextWebSocketHandler() {},
                        new WebSocketHttpHeaders(),
                        URI.create("ws://localhost:" + port + "/ws"))
                    .get())
        .isInstanceOf(Exception.class);
  }
}
