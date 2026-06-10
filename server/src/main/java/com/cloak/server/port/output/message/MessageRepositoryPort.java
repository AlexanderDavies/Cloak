package com.cloak.server.port.output.message;

import com.cloak.server.domain.message.Message;
import com.cloak.server.domain.message.MessageId;
import java.util.Optional;

/** Output port: persists and retrieves encrypted messages (ciphertext stored byte-for-byte). */
public interface MessageRepositoryPort {
  /**
   * Persists a message, storing its ciphertext byte-for-byte.
   *
   * @param message the message to persist
   */
  void save(Message message);

  /**
   * Looks up a persisted message by its identity.
   *
   * @param id the message identity
   * @return the message if present, otherwise empty
   */
  Optional<Message> find(MessageId id);
}
