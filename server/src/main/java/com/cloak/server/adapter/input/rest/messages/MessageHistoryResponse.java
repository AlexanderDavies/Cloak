package com.cloak.server.adapter.input.rest.messages;

import com.cloak.server.port.output.message.MessageHistoryQueryPort.HistoryRow;
import com.cloak.server.usecase.FetchMessageHistoryUseCase.Page;
import java.util.Base64;
import java.util.List;

/**
 * Wire DTO for {@code GET /v1/messages} — a page of the caller's undelivered backlog. Ciphertext is
 * base64-encoded; every field is routing metadata or opaque ciphertext, never plaintext (root
 * CLAUDE.md §0.6; field-by-field justification in {@code
 * docs/contracts/slice3-message-history.md}). There is no {@code toSub} field — the recipient is
 * always the authenticated caller.
 */
public record MessageHistoryResponse(List<Item> messages, boolean hasMore) {

  /**
   * One routed message: routing metadata, base64-encoded ciphertext, and its resumable cursor.
   * Field names/nesting are fixture-locked ({@code
   * docs/contracts/fixtures/slice3-message-history.json}); {@code ciphertext} holds base64 text,
   * matching the established convention (e.g. {@code PreKeyBundleResponse.identityKey}) rather than
   * a {@code *Base64}-suffixed name.
   */
  public record Item(
      String messageId,
      String fromSub,
      int fromDeviceId,
      int toDeviceId,
      int messageType,
      String conversationId,
      String ciphertext,
      String cursor) {}

  /**
   * Maps a use-case page to the wire DTO, base64-encoding each row's ciphertext and encoding its
   * cursor.
   *
   * @param page the fetched page from {@link FetchMessageHistoryUseCase}
   * @return the wire DTO ready for serialisation
   */
  public static MessageHistoryResponse from(Page page) {
    var encoder = Base64.getEncoder();
    List<Item> items = page.rows().stream().map(row -> toItem(row, encoder)).toList();
    return new MessageHistoryResponse(items, page.hasMore());
  }

  private static Item toItem(HistoryRow row, Base64.Encoder encoder) {
    return new Item(
        row.messageId(),
        row.fromSub(),
        row.fromDeviceId(),
        row.toDeviceId(),
        row.messageType(),
        row.conversationId(),
        encoder.encodeToString(row.ciphertext()),
        row.cursor().encode());
  }
}
