package com.cloak.server.domain.message;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Deterministic canonical conversation id: lowercase SHA-256 hex of the sorted, ':'-joined subs.
 */
public final class ConversationId {
  private ConversationId() {}

  /**
   * Computes the canonical id for a 1:1 pair. Order-independent (members are sorted first).
   *
   * @param subA one member sub
   * @param subB the other member sub
   * @return lowercase SHA-256 hex of {@code sorted(subs).join(":")}
   */
  public static String canonical(String subA, String subB) {
    List<String> members = List.of(subA, subB).stream().sorted().toList();
    String joined = String.join(":", members);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e); // never on a JRE
    }
  }
}
