package com.cloak.server.adapter.input.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloak.server.adapter.input.websocket.WebSocketSessionRegistry;
import com.cloak.server.adapter.output.kafka.message.OutboundEnvelope;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

class OutboundMessageConsumerTest {

  private final WebSocketSessionRegistry registry = mock(WebSocketSessionRegistry.class);
  private final OutboundMessageConsumer consumer =
      new OutboundMessageConsumer(registry, JsonMapper.builder().build());

  private OutboundEnvelope envelope() {
    return OutboundEnvelope.newBuilder()
        .setMessageId("11111111-1111-1111-1111-111111111111")
        .setToSub("bob-sub")
        .setFromSub("alice-sub")
        .setToDeviceId(1)
        .setFromDeviceId(1)
        .setMessageType(3)
        .setCiphertext(ByteBuffer.wrap(new byte[] {1, 2, 3}))
        .build();
  }

  @Test
  void openSession_receivesDeliveryFrameWithRoutingFields() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.isOpen()).thenReturn(true);
    when(registry.sessionsFor("bob-sub")).thenReturn(Set.of(session));

    consumer.onOutbound(envelope());

    var captor = ArgumentCaptor.forClass(TextMessage.class);
    verify(session).sendMessage(captor.capture());
    var frame = JsonMapper.builder().build().readTree(captor.getValue().getPayload());
    assertThat(frame.get("messageId").asString()).isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(frame.get("toSub").asString()).isEqualTo("bob-sub");
    assertThat(frame.get("fromSub").asString()).isEqualTo("alice-sub");
    assertThat(frame.get("toDeviceId").asInt()).isEqualTo(1);
    assertThat(frame.get("fromDeviceId").asInt()).isEqualTo(1);
    assertThat(frame.get("messageType").asInt()).isEqualTo(3);
  }

  @Test
  void closedSession_isSkipped() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.isOpen()).thenReturn(false);
    when(registry.sessionsFor("bob-sub")).thenReturn(Set.of(session));

    consumer.onOutbound(envelope());

    verify(session, never()).sendMessage(any());
  }

  @Test
  void failingSession_doesNotAbortDeliveryToOthers() throws Exception {
    WebSocketSession bad = mock(WebSocketSession.class);
    WebSocketSession good = mock(WebSocketSession.class);
    when(bad.isOpen()).thenReturn(true);
    when(good.isOpen()).thenReturn(true);
    doThrow(new IOException("socket closed")).when(bad).sendMessage(any());
    when(registry.sessionsFor("bob-sub")).thenReturn(Set.of(bad, good));

    consumer.onOutbound(envelope()); // must not throw despite the failing session

    verify(good).sendMessage(any(TextMessage.class));
  }
}
