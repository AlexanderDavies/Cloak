package com.cloak.server.domain.message;

import java.util.Objects;

/** Identity of a {@link Message}: the client-supplied UUID string used for dedupe/ordering. */
public record MessageId(String value) {
  /** Compact constructor rejecting a null {@code value}. */
  public MessageId {
    Objects.requireNonNull(value, "messageId");
  }
}
