package com.cloak.server.domain.message;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque keyset cursor over {@code (created_at, id)}. Encoded so clients don't couple to columns.
 */
public record MessageCursor(Instant createdAt, UUID id) {

  /** Encodes this cursor as an opaque, URL-safe, unpadded base64 token. */
  public String encode() {
    long micros = createdAt.getEpochSecond() * 1_000_000L + createdAt.getNano() / 1_000L;
    String raw = micros + ":" + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Decodes a cursor token, or {@code null} for a blank/absent cursor (start from the beginning).
   *
   * @param token the opaque cursor token, or {@code null}/blank
   * @return the decoded cursor, or {@code null} to start from the beginning
   * @throws IllegalArgumentException if {@code token} is non-blank but not a valid cursor
   */
  public static MessageCursor decode(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    try {
      String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
      int sep = raw.lastIndexOf(':');
      long micros = Long.parseLong(raw.substring(0, sep));
      Instant ts = Instant.ofEpochSecond(micros / 1_000_000L, (micros % 1_000_000L) * 1_000L);
      return new MessageCursor(ts, UUID.fromString(raw.substring(sep + 1)));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("malformed cursor");
    }
  }
}
