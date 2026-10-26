package com.cloak.server.adapter.input.websocket;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloak.server.usecase.InvalidFrameException;
import com.cloak.server.usecase.RouteMessageUseCase;
import io.micrometer.observation.ObservationRegistry;
import java.security.Principal;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

class MessageWebSocketHandlerTest {

  private final RouteMessageUseCase useCase = mock(RouteMessageUseCase.class);

  private final MessageWebSocketHandler handler =
      new MessageWebSocketHandler(
          useCase,
          new WebSocketSessionRegistry(),
          JsonMapper.builder().build(),
          ObservationRegistry.NOOP);

  @Test
  void connection_withoutJwtPrincipal_isRejected() {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getPrincipal()).thenReturn(mock(Principal.class)); // not a JwtAuthenticationToken

    assertThatThrownBy(() -> handler.afterConnectionEstablished(session))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectedFrame_isSwallowedAsDrop() {
    doThrow(new InvalidFrameException("bad frame")).when(useCase).route(any());

    assertThatCode(() -> handler.handleTextMessage(authenticatedSession(), frame()))
        .doesNotThrowAnyException();
  }

  /**
   * A frame that reaches persistence or publication and then fails is NOT a dropped frame — the row
   * may already be committed. Swallowing it would log a false "dropped" claim and hide a genuine
   * infrastructure fault, so anything other than {@link InvalidFrameException} must propagate.
   */
  @Test
  void infrastructureFailureAfterValidation_propagates() {
    doThrow(new IllegalArgumentException("Invalid UUID string")).when(useCase).route(any());

    assertThatThrownBy(() -> handler.handleTextMessage(authenticatedSession(), frame()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private WebSocketSession authenticatedSession() {
    WebSocketSession session = mock(WebSocketSession.class);
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("alice-sub")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    when(session.getPrincipal()).thenReturn(new JwtAuthenticationToken(jwt));
    return session;
  }

  private TextMessage frame() {
    String ciphertext = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
    return new TextMessage(
        ("{\"messageId\":\"11111111-1111-1111-1111-111111111111\",\"toSub\":\"bob-sub\","
                + "\"toDeviceId\":1,\"fromDeviceId\":1,\"messageType\":2,"
                + "\"conversationId\":\"c\",\"ciphertext\":\"%s\"}")
            .formatted(ciphertext));
  }
}
