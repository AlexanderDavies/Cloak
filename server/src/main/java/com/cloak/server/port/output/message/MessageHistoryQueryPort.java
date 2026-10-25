package com.cloak.server.port.output.message;

import com.cloak.server.domain.message.MessageCursor;
import java.util.List;

/** Output port: stateless keyset-paginated read of a recipient's routed message backlog. */
public interface MessageHistoryQueryPort {

  /**
   * Returns up to {@code limit} messages addressed to {@code recipientSub}, ordered by {@code
   * (created_at, id)} ascending, strictly after {@code cursor}.
   *
   * @param recipientSub the recipient's Keycloak {@code sub} (from the authenticated JWT)
   * @param cursor the keyset cursor to resume after, or {@code null} to start from the beginning
   * @param limit the page size (already capped by the caller)
   * @return the page of rows, in {@code (created_at, id)} order
   */
  List<HistoryRow> page(String recipientSub, MessageCursor cursor, int limit);

  /** One routed message: routing metadata, opaque ciphertext, and its resumable cursor. */
  record HistoryRow(
      String messageId,
      String fromSub,
      int fromDeviceId,
      int toDeviceId,
      int messageType,
      String conversationId,
      byte[] ciphertext,
      MessageCursor cursor) {}
}
