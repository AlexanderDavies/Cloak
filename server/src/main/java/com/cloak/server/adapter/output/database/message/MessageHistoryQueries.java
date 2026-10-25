package com.cloak.server.adapter.output.database.message;

/**
 * SQL for {@link MessageHistoryQueryAdapter}, grouped here so the adapter stays mapping logic.
 *
 * <p>Both queries serve only <em>settled</em> rows: {@code created_at <= now() - settle lag}.
 * {@code created_at} defaults to {@code now()}, the inserting transaction's start time rather than
 * its commit time, so a row can commit stamped behind a cursor the client already holds; a keyset
 * pull would then skip it forever. Waiting out the settle window means no earlier-stamped row can
 * still be committing. Measured on the database clock, so app/DB clock skew cannot defeat it.
 */
final class MessageHistoryQueries {

  private MessageHistoryQueries() {}

  static final String FROM_START =
      """
      SELECT id, sender_sub, recipient_sub, conversation_id, sender_device_id, recipient_device_id,
             message_type, ciphertext, created_at
      FROM encrypted_message
      WHERE recipient_sub = :me
        AND created_at <= now() - make_interval(secs => :settleLagSeconds)
      ORDER BY created_at, id LIMIT :limit
      """;

  /**
   * {@code (created_at, id) > (:ts, :id)} is a true row comparison, not two separate predicates —
   * it stays correct when two rows share the same {@code created_at} (stable under timestamp
   * collisions, unlike a naive {@code created_at > :ts}).
   */
  static final String AFTER_CURSOR =
      """
      SELECT id, sender_sub, recipient_sub, conversation_id, sender_device_id, recipient_device_id,
             message_type, ciphertext, created_at
      FROM encrypted_message
      WHERE recipient_sub = :me AND (created_at, id) > (:ts, :id)
        AND created_at <= now() - make_interval(secs => :settleLagSeconds)
      ORDER BY created_at, id LIMIT :limit
      """;
}
