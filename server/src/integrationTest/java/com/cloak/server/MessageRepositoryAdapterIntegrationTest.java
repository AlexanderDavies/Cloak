package com.cloak.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloak.server.domain.message.Ciphertext;
import com.cloak.server.domain.message.ConversationId;
import com.cloak.server.domain.message.Message;
import com.cloak.server.domain.message.MessageId;
import com.cloak.server.port.output.message.MessageRepositoryPort;
import com.cloak.server.support.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Slice 3: {@code encrypted_message} persists the real routing/decrypt fields (Flyway V4) instead
 * of reconstructing them as read-back sentinels.
 */
class MessageRepositoryAdapterIntegrationTest extends IntegrationTestBase {

  @Autowired MessageRepositoryPort repository;

  @Test
  void persistsAndReadsBackRealRoutingFields() {
    var id = new MessageId(UUID.randomUUID().toString());
    var convo = ConversationId.canonical("alice-sub", "bob-sub");
    var message =
        Message.create(
            id, "alice-sub", "bob-sub", convo, 1, 1, 3, new Ciphertext(new byte[] {1, 2, 3}));
    repository.save(message);

    var found = repository.find(id).orElseThrow();
    assertThat(found.conversationId()).isEqualTo(convo);
    assertThat(found.senderDeviceId()).isEqualTo(1);
    assertThat(found.recipientDeviceId()).isEqualTo(1);
    assertThat(found.messageType()).isEqualTo(3);
    assertThat(found.ciphertext().value()).containsExactly(1, 2, 3);
  }
}
