package com.cloak.server.port.output.message;

import com.cloak.server.domain.message.Message;

/** Output port: publishes a routed message onto the delivery backbone (Kafka). */
public interface MessagePublisherPort {
  /**
   * Publishes a routed message onto the delivery backbone for fan-out to the recipient.
   *
   * @param message the persisted message to publish (ciphertext stays opaque)
   */
  void publish(Message message);
}
