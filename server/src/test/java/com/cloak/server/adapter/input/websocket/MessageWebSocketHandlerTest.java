package com.cloak.server.adapter.input.websocket;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloak.server.usecase.RouteMessageUseCase;
import io.micrometer.observation.ObservationRegistry;
import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

class MessageWebSocketHandlerTest {

  private final MessageWebSocketHandler handler =
      new MessageWebSocketHandler(
          mock(RouteMessageUseCase.class),
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
}
