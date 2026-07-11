package com.cloak.server.adapter.output.database.message;

import com.cloak.server.domain.message.Ciphertext;
import com.cloak.server.domain.message.Message;
import com.cloak.server.domain.message.MessageId;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class MessageRowMapper {
  // Slice 2 does not persist integer device numbers or messageType (deferred to the Slice 3 history
  // migration). Until then toDomain() reconstructs them as these sentinels; the read-back path is
  // exercised only by Slice 2 tests, never by live delivery. TODO(Slice3): persist + read real
  // values.
  private static final int SENTINEL_DEVICE_ID = 1;
  private static final int SENTINEL_MESSAGE_TYPE = 2;

  EncryptedMessageEntity toEntity(Message m) {
    // device_id column is left null this slice; integer device numbers are not persisted until
    // Slice 3 (history migration). Persist messageId, senderSub, recipientSub, ciphertext only.
    return new EncryptedMessageEntity(
        UUID.fromString(m.id().value()),
        m.senderSub(),
        m.recipientSub(),
        null,
        m.ciphertext().value());
  }

  Message toDomain(EncryptedMessageEntity e) {
    return Message.create(
        new MessageId(e.getId().toString()),
        e.getSenderSub(),
        e.getRecipientSub(),
        SENTINEL_DEVICE_ID,
        SENTINEL_DEVICE_ID,
        SENTINEL_MESSAGE_TYPE,
        new Ciphertext(e.getCiphertext()));
  }
}
