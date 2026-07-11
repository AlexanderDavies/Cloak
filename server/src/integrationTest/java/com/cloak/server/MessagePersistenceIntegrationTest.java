package com.cloak.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloak.server.domain.message.Ciphertext;
import com.cloak.server.domain.message.Message;
import com.cloak.server.domain.message.MessageId;
import com.cloak.server.port.output.message.MessageRepositoryPort;
import com.cloak.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MessagePersistenceIntegrationTest extends IntegrationTestBase {

  @Autowired MessageRepositoryPort repository;

  @Test
  void savedMessage_reloadsCiphertextUnchanged() {
    byte[] cipher = {4, 8, 15, 16, 23, 42};
    var id = new MessageId("22222222-2222-2222-2222-222222222222");
    // device_id column is left null (Slice 2): integer device numbers are not persisted until
    // Slice 3. The test's intent is the byte-for-byte ciphertext round-trip.
    repository.save(Message.create(id, "alice-sub", "bob-sub", 1, 1, 2, new Ciphertext(cipher)));

    var loaded = repository.find(id).orElseThrow();
    assertThat(loaded.ciphertext().value()).containsExactly(cipher);
    assertThat(loaded.recipientSub()).isEqualTo("bob-sub");
  }
}
