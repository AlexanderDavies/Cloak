package com.cloak.server.adapter.output.kafka.message;

import com.cloak.server.domain.message.Message;
import com.cloak.server.port.output.message.MessagePublisherPort;
import java.nio.ByteBuffer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes a {@link Message} as an Avro {@link OutboundEnvelope} on {@code
 * cloak.messages.outbound}, keyed by the recipient {@code sub} (fan-out / per-recipient ordering,
 * {@code queue/CLAUDE.md}). The {@code ciphertext} is opaque bytes — never inspected, never logged.
 */
@Component
class KafkaMessagePublisherAdapter implements MessagePublisherPort {

  static final String TOPIC = "cloak.messages.outbound";

  private final KafkaTemplate<String, OutboundEnvelope> kafka;

  KafkaMessagePublisherAdapter(KafkaTemplate<String, OutboundEnvelope> kafka) {
    this.kafka = kafka;
  }

  @Override
  public void publish(Message m) {
    var env =
        OutboundEnvelope.newBuilder()
            .setMessageId(m.id().value())
            .setToSub(m.recipientSub())
            .setFromSub(m.senderSub())
            .setDeviceId(m.deviceId())
            .setCiphertext(ByteBuffer.wrap(m.ciphertext().value()))
            .build();
    kafka.send(TOPIC, m.recipientSub(), env);
  }
}
