package com.cloak.server.usecase;

import com.cloak.server.domain.message.Ciphertext;
import com.cloak.server.domain.message.Message;
import com.cloak.server.domain.message.MessageId;
import com.cloak.server.port.output.message.MessagePublisherPort;
import com.cloak.server.port.output.message.MessageRepositoryPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists then publishes a routed message (§5.2 persist-then-publish). */
@Component
public class RouteMessageUseCase {

  private final MessageRepositoryPort repository;
  private final MessagePublisherPort publisher;
  private final TransactionTemplate tx;

  /**
   * Creates the use case.
   *
   * @param repository persistence port (stores ciphertext byte-for-byte)
   * @param publisher delivery-backbone port (Kafka)
   * @param tx transaction template wrapping the persist step
   */
  public RouteMessageUseCase(
      MessageRepositoryPort repository, MessagePublisherPort publisher, TransactionTemplate tx) {
    this.repository = repository;
    this.publisher = publisher;
    this.tx = tx;
  }

  /**
   * Persists the message in a transaction, then publishes it for delivery after commit.
   *
   * @param cmd the routing command (sender already resolved from the authenticated principal)
   */
  public void route(RouteMessageCommand cmd) {
    var message =
        Message.create(
            new MessageId(cmd.messageId()),
            cmd.senderSub(),
            cmd.recipientSub(),
            cmd.deviceId(),
            new Ciphertext(cmd.ciphertext()));
    tx.executeWithoutResult(s -> repository.save(message)); // persist in transaction
    publisher.publish(message); // publish after commit (§5.2 persist-then-publish)
  }
}
