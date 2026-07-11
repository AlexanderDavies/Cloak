package com.cloak.server.adapter.input.kafka;

import com.cloak.server.adapter.input.websocket.WebSocketSessionRegistry;
import com.cloak.server.adapter.output.kafka.message.OutboundEnvelope;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbound delivery adapter. Consumes the Avro {@link OutboundEnvelope} off {@code
 * cloak.messages.outbound} (keyed by recipient {@code sub}), rebuilds the cleartext delivery
 * envelope JSON of {@code docs/contracts/slice2-message-envelope.md}, and fans it out — unchanged —
 * to every open WebSocket session the recipient currently holds.
 *
 * <p>The {@code ciphertext} is opaque: it is only re-encoded as base64 and forwarded. It is never
 * decoded for inspection, never logged, never decrypted (§0.6 privacy).
 */
@Component
public class OutboundMessageConsumer {

  private static final Logger log = LoggerFactory.getLogger(OutboundMessageConsumer.class);

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
   * of the recipient. The ciphertext is re-encoded as base64 and forwarded unchanged. Delivery is
   * best-effort per session: a failing send is logged and skipped so it neither aborts delivery to
   * the recipient's other sessions nor fails the Kafka record (which would redeliver duplicates to
   * sessions already served).
   *
   * @param env the Avro envelope published by the router, keyed by recipient {@code sub}
   */
  @KafkaListener(topics = "cloak.messages.outbound", groupId = "cloak-server")
  public void onOutbound(OutboundEnvelope env) {
    String toSub = env.getToSub().toString();
    ByteBuffer buf = env.getCiphertext();
    byte[] ciphertext = new byte[buf.remaining()];
    buf.duplicate().get(ciphertext); // duplicate() so the source buffer's position is untouched

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("messageId", env.getMessageId().toString());
    payload.put("toSub", toSub);
    payload.put("fromSub", env.getFromSub().toString());
    payload.put("toDeviceId", env.getToDeviceId());
    payload.put("fromDeviceId", env.getFromDeviceId());
    payload.put("messageType", env.getMessageType());
    // Opaque ciphertext re-encoded as base64 and forwarded unchanged — never decrypted/inspected.
    payload.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
    TextMessage frame = new TextMessage(jsonMapper.writeValueAsString(payload));

    for (WebSocketSession session : registry.sessionsFor(toSub)) {
      if (!session.isOpen()) {
        continue;
      }
      try {
        session.sendMessage(frame);
      } catch (IOException e) {
        // A single recipient session must not abort delivery to the others; routing sub only,
        // never the frame/ciphertext.
        log.warn(
            "WebSocket delivery to a session for recipient {} failed: {}", toSub, e.toString());
      }
    }
  }
}
