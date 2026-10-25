package com.cloak.server.adapter.output.database.message;

import com.cloak.server.domain.message.MessageCursor;
import com.cloak.server.port.output.message.MessageHistoryQueryPort;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC adapter implementing {@link MessageHistoryQueryPort}. Read-only and stateless: every call is
 * a self-contained keyset page query, no server-side delivery bookkeeping. Uses {@link JdbcClient}
 * (mirrors {@code PreKeyBundleQueryAdapter}'s style) rather than JPA for the explicit
 * row-comparison keyset predicate.
 */
@Repository
class MessageHistoryQueryAdapter implements MessageHistoryQueryPort {

  private final JdbcClient jdbc;
  private final double settleLagSeconds;

  MessageHistoryQueryAdapter(
      JdbcClient jdbc, @Value("${cloak.messages.backlog-settle-lag:3s}") Duration settleLag) {
    this.jdbc = jdbc;
    this.settleLagSeconds = settleLag.toMillis() / 1000.0;
  }

  @Override
  public List<HistoryRow> page(String recipientSub, MessageCursor cursor, int limit) {
    var spec =
        (cursor == null)
            ? jdbc.sql(MessageHistoryQueries.FROM_START)
                .param("me", recipientSub)
                .param("settleLagSeconds", settleLagSeconds)
                .param("limit", limit)
            : jdbc.sql(MessageHistoryQueries.AFTER_CURSOR)
                .param("me", recipientSub)
                .param("settleLagSeconds", settleLagSeconds)
                .param("limit", limit)
                .param("ts", OffsetDateTime.ofInstant(cursor.createdAt(), ZoneOffset.UTC))
                .param("id", cursor.id());
    return spec.query(
            (rs, n) -> {
              UUID id = rs.getObject("id", UUID.class);
              Instant createdAt = rs.getObject("created_at", OffsetDateTime.class).toInstant();
              return new HistoryRow(
                  id.toString(),
                  rs.getString("sender_sub"),
                  rs.getInt("sender_device_id"),
                  rs.getInt("recipient_device_id"),
                  rs.getInt("message_type"),
                  rs.getString("conversation_id"),
                  rs.getBytes("ciphertext"),
                  new MessageCursor(createdAt, id));
            })
        .list();
  }
}
