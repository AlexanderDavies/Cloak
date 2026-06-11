package com.cloak.server.adapter.input.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class WebSocketSessionRegistryTest {

  private final WebSocketSessionRegistry registry = new WebSocketSessionRegistry();

  @Test
  void addedSession_isReturnedForItsSub() {
    WebSocketSession session = mock(WebSocketSession.class);
    registry.add("bob-sub", session);
    assertThat(registry.sessionsFor("bob-sub")).containsExactly(session);
  }

  @Test
  void unknownSub_returnsEmptySet() {
    assertThat(registry.sessionsFor("nobody")).isEmpty();
  }

  @Test
  void removingOneOfTwo_keepsTheOther() {
    WebSocketSession a = mock(WebSocketSession.class);
    WebSocketSession b = mock(WebSocketSession.class);
    registry.add("bob-sub", a);
    registry.add("bob-sub", b);

    registry.remove("bob-sub", a);

    assertThat(registry.sessionsFor("bob-sub")).containsExactly(b);
  }

  @Test
  void removingLastSession_clearsTheSub_andReAddWorks() {
    WebSocketSession a = mock(WebSocketSession.class);
    registry.add("bob-sub", a);
    registry.remove("bob-sub", a);
    assertThat(registry.sessionsFor("bob-sub")).isEmpty();

    // The sub's entry was dropped (no empty-set leak); a fresh connection still registers cleanly.
    WebSocketSession b = mock(WebSocketSession.class);
    registry.add("bob-sub", b);
    assertThat(registry.sessionsFor("bob-sub")).containsExactly(b);
  }
}
