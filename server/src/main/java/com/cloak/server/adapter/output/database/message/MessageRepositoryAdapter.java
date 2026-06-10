package com.cloak.server.adapter.output.database.message;

import com.cloak.server.domain.message.Message;
import com.cloak.server.domain.message.MessageId;
import com.cloak.server.port.output.message.MessageRepositoryPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class MessageRepositoryAdapter implements MessageRepositoryPort {
  private final SpringDataMessageRepository jpa;
  private final MessageRowMapper mapper;

  MessageRepositoryAdapter(SpringDataMessageRepository jpa, MessageRowMapper mapper) {
    this.jpa = jpa;
    this.mapper = mapper;
  }

  @Override
  public void save(Message message) {
    jpa.save(mapper.toEntity(message));
  }

  @Override
  public Optional<Message> find(MessageId id) {
    return jpa.findById(UUID.fromString(id.value())).map(mapper::toDomain);
  }
}
