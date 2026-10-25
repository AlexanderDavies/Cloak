package com.cloak.server.domain.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MessageCursor}: encode/decode round-trip and malformed/blank handling. */
class MessageCursorTest {

  @Test
  void encodeThenDecode_roundTripsToTheSameCursor() {
    Instant createdAt = Instant.parse("2026-07-29T12:34:56.789123Z");
    UUID id = UUID.randomUUID();
    var cursor = new MessageCursor(createdAt, id);

    var decoded = MessageCursor.decode(cursor.encode());

    assertThat(decoded).isEqualTo(cursor);
    assertThat(decoded.createdAt()).isEqualTo(createdAt);
    assertThat(decoded.id()).isEqualTo(id);
  }

  @Test
  void encode_truncatesToMicrosecondPrecision() {
    // Instant carries nanosecond precision; MessageCursor stores microseconds, so a decoded
    // round-trip drops sub-microsecond precision rather than silently corrupting the value.
    Instant nanoPrecise = Instant.parse("2026-07-29T12:34:56.789123456Z");
    var cursor = new MessageCursor(nanoPrecise, UUID.randomUUID());

    var decoded = MessageCursor.decode(cursor.encode());

    assertThat(decoded.createdAt()).isEqualTo(Instant.parse("2026-07-29T12:34:56.789123Z"));
  }

  @Test
  void decode_null_returnsNull() {
    assertThat(MessageCursor.decode(null)).isNull();
  }

  @Test
  void decode_blank_returnsNull() {
    assertThat(MessageCursor.decode("")).isNull();
    assertThat(MessageCursor.decode("   ")).isNull();
  }

  @Test
  void decode_malformedBase64_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> MessageCursor.decode("not valid base64!!!"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decode_wellFormedBase64WithoutColon_throwsIllegalArgumentException() {
    String garbage =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("no-colon-here".getBytes());
    assertThatThrownBy(() -> MessageCursor.decode(garbage))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decode_validMicrosButInvalidUuid_throwsIllegalArgumentException() {
    String raw = "1722200000000000:not-a-uuid";
    String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
    assertThatThrownBy(() -> MessageCursor.decode(token))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
