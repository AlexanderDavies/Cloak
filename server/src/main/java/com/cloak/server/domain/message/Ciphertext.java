package com.cloak.server.domain.message;

import java.util.Objects;

/** Opaque encrypted bytes. The server never reads or decrypts these. */
public record Ciphertext(byte[] value) {
  /** Compact constructor rejecting null bytes. */
  public Ciphertext {
    Objects.requireNonNull(value, "ciphertext");
  }

  @Override
  public String toString() {
    return "Ciphertext[" + value.length + " bytes]"; // never dump bytes (§0.6.4)
  }
}
