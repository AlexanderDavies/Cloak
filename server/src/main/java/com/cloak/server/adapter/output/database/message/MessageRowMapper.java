package com.cloak.server.adapter.output.database.message;

import com.cloak.server.domain.message.Ciphertext;
import com.cloak.server.domain.message.Message;
import com.cloak.server.domain.message.MessageId;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class MessageRowMapper {
  EncryptedMessageEntity toEntity(Message m) {
    return new EncryptedMessageEntity(
        UUID.fromString(m.id().value()),
        m.senderSub(),
        m.recipientSub(),
        m.deviceId() == null ? null : UUID.fromString(m.deviceId()),
        m.ciphertext().value());
  }

  Message toDomain(EncryptedMessageEntity e) {
    return Message.create(
        new MessageId(e.getId().toString()),
        e.getSenderSub(),
        e.getRecipientSub(),
        e.getDeviceId() == null ? null : e.getDeviceId().toString(),
        new Ciphertext(e.getCiphertext()));
  }
}
