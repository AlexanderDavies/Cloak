package com.cloak.server.domain.message;

/** Routing-metadata + opaque ciphertext. No plaintext field, no decrypt (§0.6.3). */
public final class Message {
  private final MessageId id;
  private final String senderSub;
  private final String recipientSub;
  private final int senderDeviceId;
  private final int recipientDeviceId;
  private final int messageType;
  private final Ciphertext ciphertext;

  private Message(
      MessageId id,
      String senderSub,
      String recipientSub,
      int senderDeviceId,
      int recipientDeviceId,
      int messageType,
      Ciphertext ciphertext) {
    this.id = id;
    this.senderSub = senderSub;
    this.recipientSub = recipientSub;
    this.senderDeviceId = senderDeviceId;
    this.recipientDeviceId = recipientDeviceId;
    this.messageType = messageType;
    this.ciphertext = ciphertext;
  }

  /**
   * Factory for a routed message.
   *
   * @param id message identity
   * @param senderSub authenticated sender {@code sub}
   * @param recipientSub recipient {@code sub}
   * @param senderDeviceId libsignal integer device number of the sender
   * @param recipientDeviceId libsignal integer device number of the recipient
   * @param messageType {@code 2} = normal SignalMessage, {@code 3} = PreKeySignalMessage
   * @param ciphertext opaque encrypted payload
   * @return the assembled message
   */
  public static Message create(
      MessageId id,
      String senderSub,
      String recipientSub,
      int senderDeviceId,
      int recipientDeviceId,
      int messageType,
      Ciphertext ciphertext) {
    return new Message(
        id, senderSub, recipientSub, senderDeviceId, recipientDeviceId, messageType, ciphertext);
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

  /** Returns the libsignal integer device number of the sender. */
  public int senderDeviceId() {
    return senderDeviceId;
  }

  /** Returns the libsignal integer device number of the recipient. */
  public int recipientDeviceId() {
    return recipientDeviceId;
  }

  /**
   * Returns the message type discriminator: {@code 2} = normal SignalMessage, {@code 3} =
   * PreKeySignalMessage.
   */
  public int messageType() {
    return messageType;
  }

  /** Returns the opaque ciphertext. */
  public Ciphertext ciphertext() {
    return ciphertext;
  }
}
