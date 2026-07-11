package com.cloak.server.usecase;

import com.cloak.server.domain.message.Ciphertext;
import com.cloak.server.domain.message.Message;
import com.cloak.server.domain.message.MessageId;
import com.cloak.server.port.output.message.MessagePublisherPort;
import com.cloak.server.port.output.message.MessageRepositoryPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists then publishes a routed message (§5.2 persist-then-publish). */
@Component
public class RouteMessageUseCase {

  private final MessageRepositoryPort repository;
  private final MessagePublisherPort publisher;
  private final TransactionTemplate tx;
  // Business-event counter (§10.4). Carries no sub/ciphertext — just a count of routed messages.
  private final Counter routed;

  /**
   * Creates the use case.
   *
   * @param repository persistence port (stores ciphertext byte-for-byte)
   * @param publisher delivery-backbone port (Kafka)
   * @param tx transaction template wrapping the persist step
   * @param registry meter registry for the routed-message counter
   */
  public RouteMessageUseCase(
      MessageRepositoryPort repository,
      MessagePublisherPort publisher,
      TransactionTemplate tx,
      MeterRegistry registry) {
    this.repository = repository;
    this.publisher = publisher;
    this.tx = tx;
    this.routed =
        Counter.builder("cloak.messages.routed")
            .description("Count of messages persisted and published for delivery.")
            .register(registry);
  }

  /**
   * Persists the message in a transaction, then publishes it for delivery after commit.
   *
   * @param cmd the routing command (sender already resolved from the authenticated principal)
   */
  @Observed(name = "cloak.message.route") // emits a use-case latency timer + a span (§10.4)
  public void route(RouteMessageCommand cmd) {
    var message =
        Message.create(
            new MessageId(cmd.messageId()),
            cmd.senderSub(),
            cmd.recipientSub(),
            cmd.senderDeviceId(),
            cmd.recipientDeviceId(),
            cmd.messageType(),
            new Ciphertext(cmd.ciphertext()));
    tx.executeWithoutResult(s -> repository.save(message)); // persist in transaction
    publisher.publish(message); // publish after commit (§5.2 persist-then-publish)
    routed.increment();
  }
}
