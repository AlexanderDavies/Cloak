package com.cloak.server.usecase;

import com.cloak.server.domain.message.MessageCursor;
import com.cloak.server.port.output.message.MessageHistoryQueryPort;
import io.micrometer.observation.annotation.Observed;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Fetches a page of the caller's undelivered message backlog. Stateless: the server keeps no
 * per-client "last delivered" pointer — every call is a self-contained keyset page driven by the
 * caller-supplied cursor (root CLAUDE.md §0.6; {@code docs/contracts/slice3-message-history.md}).
 */
@Component
public class FetchMessageHistoryUseCase {

  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 200;

  private final MessageHistoryQueryPort query;

  FetchMessageHistoryUseCase(MessageHistoryQueryPort query) {
    this.query = query;
  }

  /**
   * Fetches one page of {@code recipientSub}'s backlog, ordered by {@code (created_at, id)}.
   *
   * @param recipientSub the recipient's Keycloak {@code sub} (from the authenticated JWT, never a
   *     caller-supplied parameter)
   * @param cursorToken the opaque cursor to resume after, or {@code null}/blank to start from the
   *     beginning
   * @param limit the requested page size; non-positive or absent falls back to {@link
   *     #DEFAULT_LIMIT}, and every value is capped at {@link #MAX_LIMIT}
   * @return the page of rows plus whether another page is available
   * @throws IllegalArgumentException if {@code cursorToken} is non-blank but malformed
   */
  @Observed(name = "cloak.messages.history")
  public Page fetch(String recipientSub, String cursorToken, Integer limit) {
    int capped = Math.min(limit == null || limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT);
    var rows = query.page(recipientSub, MessageCursor.decode(cursorToken), capped);
    return new Page(rows, rows.size() == capped);
  }

  /** One page of backlog rows plus whether another page is available. */
  public record Page(List<MessageHistoryQueryPort.HistoryRow> rows, boolean hasMore) {}
}
