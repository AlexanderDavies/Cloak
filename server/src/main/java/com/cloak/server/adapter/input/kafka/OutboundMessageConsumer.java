package com.cloak.server.adapter.input.kafka;

import com.cloak.server.adapter.input.websocket.WebSocketSessionRegistry;
import com.cloak.server.adapter.output.kafka.message.OutboundEnvelope;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbound delivery adapter. Consumes the Avro {@link OutboundEnvelope} off {@code
 * cloak.messages.outbound} (keyed by recipient {@code sub}), rebuilds the cleartext delivery
 * envelope JSON of {@code docs/contracts/phase0-message-envelope.md}, and fans it out — unchanged —
 * to every open WebSocket session the recipient currently holds.
 *
 * <p>The {@code ciphertext} is opaque: it is only re-encoded as base64 and forwarded. It is never
 * decoded for inspection, never logged, never decrypted (§0.6 privacy).
 */
@Component
public class OutboundMessageConsumer {

  private final WebSocketSessionRegistry registry;
  private final JsonMapper jsonMapper;

  /**
   * Creates the consumer.
   *
   * @param registry the live WebSocket session registry used to address connected recipients
   * @param jsonMapper the shared Jackson 3 mapper for building the delivery frame
   */
  public OutboundMessageConsumer(WebSocketSessionRegistry registry, JsonMapper jsonMapper) {
    this.registry = registry;
    this.jsonMapper = jsonMapper;
  }

  /**
   * Consumes one outbound envelope and forwards the cleartext delivery frame to every open session
   * of the recipient. The ciphertext is re-encoded as base64 and forwarded unchanged.
   *
   * @param env the Avro envelope published by the router, keyed by recipient {@code sub}
   * @throws Exception if the JSON frame cannot be serialised or a session send fails
   */
  @KafkaListener(topics = "cloak.messages.outbound", groupId = "cloak-server")
  public void onOutbound(OutboundEnvelope env) throws Exception {
    String toSub = env.getToSub().toString();
    ByteBuffer buf = env.getCiphertext();
    byte[] ciphertext = new byte[buf.remaining()];
    buf.duplicate().get(ciphertext); // duplicate() so the source buffer's position is untouched

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("messageId", env.getMessageId().toString());
    payload.put("toSub", toSub);
    payload.put("fromSub", env.getFromSub().toString());
    payload.put("deviceId", env.getDeviceId() == null ? null : env.getDeviceId().toString());
    // Opaque ciphertext re-encoded as base64 and forwarded unchanged — never decrypted/inspected.
    payload.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
    String json = jsonMapper.writeValueAsString(payload);

    TextMessage frame = new TextMessage(json);
    for (WebSocketSession session : registry.sessionsFor(toSub)) {
      if (session.isOpen()) {
        session.sendMessage(frame);
      }
    }
  }
}
