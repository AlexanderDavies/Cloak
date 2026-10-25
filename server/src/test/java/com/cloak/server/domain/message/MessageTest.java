package com.cloak.server.domain.message;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MessageTest {
  @Test
  void ciphertextToString_neverLeaksBytes() {
    var c = new Ciphertext(new byte[] {1, 2, 3, 4});
    assertThat(c.toString()).isEqualTo("Ciphertext[4 bytes]").doesNotContain("1, 2");
  }

  @Test
  void message_exposesRoutingMetadataAndOpaqueCiphertext() {
    var convo = ConversationId.canonical("alice-sub", "bob-sub");
    var msg =
        Message.create(
            new MessageId("11111111-1111-1111-1111-111111111111"),
            "alice-sub",
            "bob-sub",
            convo,
            1,
            1,
            3,
            new Ciphertext(new byte[] {9, 9}));
    assertThat(msg.recipientSub()).isEqualTo("bob-sub");
    assertThat(msg.conversationId()).isEqualTo(convo);
    assertThat(msg.senderDeviceId()).isEqualTo(1);
    assertThat(msg.recipientDeviceId()).isEqualTo(1);
    assertThat(msg.messageType()).isEqualTo(3);
    assertThat(msg.ciphertext().value()).containsExactly(9, 9);
  }
}
