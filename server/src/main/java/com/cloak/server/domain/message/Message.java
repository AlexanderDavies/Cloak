package com.cloak.server.domain.message;

/** Routing-metadata + opaque ciphertext. No plaintext field, no decrypt (§0.6.3). */
public final class Message {
  private final MessageId id;
  private final String senderSub;
  private final String recipientSub;
  private final String deviceId;
  private final Ciphertext ciphertext;

  private Message(
      MessageId id, String senderSub, String recipientSub, String deviceId, Ciphertext ciphertext) {
    this.id = id;
    this.senderSub = senderSub;
    this.recipientSub = recipientSub;
    this.deviceId = deviceId;
    this.ciphertext = ciphertext;
  }

  /**
   * Factory for a routed message.
   *
   * @param id message identity
   * @param senderSub authenticated sender {@code sub}
   * @param recipientSub recipient {@code sub}
   * @param deviceId sender device id, or {@code null}
   * @param ciphertext opaque encrypted payload
   * @return the assembled message
   */
  public static Message create(
      MessageId id, String senderSub, String recipientSub, String deviceId, Ciphertext ciphertext) {
    return new Message(id, senderSub, recipientSub, deviceId, ciphertext);
  }

  /** Returns the message identity. */
  public MessageId id() {
    return id;
  }

  /** Returns the authenticated sender {@code sub}. */
  public String senderSub() {
    return senderSub;
  }

  /** Returns the recipient {@code sub}. */
  public String recipientSub() {
    return recipientSub;
  }

  /** Returns the sender device id, or {@code null}. */
  public String deviceId() {
    return deviceId;
  }

  /** Returns the opaque ciphertext. */
  public Ciphertext ciphertext() {
    return ciphertext;
  }
}
