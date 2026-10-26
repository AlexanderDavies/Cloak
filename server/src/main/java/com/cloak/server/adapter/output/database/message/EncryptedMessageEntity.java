package com.cloak.server.adapter.output.database.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** JPA row for {@code encrypted_message}: routing metadata plus the opaque ciphertext blob. */
@Entity
@Table(name = "encrypted_message")
public class EncryptedMessageEntity {
  @Id private UUID id;

  @Column(name = "sender_sub", nullable = false)
  private String senderSub;

  @Column(name = "recipient_sub", nullable = false)
  private String recipientSub;

  @Column(name = "conversation_id", nullable = false)
  private String conversationId;

  @Column(name = "sender_device_id", nullable = false)
  private int senderDeviceId;

  @Column(name = "recipient_device_id", nullable = false)
  private int recipientDeviceId;

  @Column(name = "message_type", nullable = false)
  private short messageType;

  @Column(name = "ciphertext", nullable = false)
  private byte[] ciphertext;

  /** JPA-required no-args constructor. */
  protected EncryptedMessageEntity() {}

  /**
   * Builds a persistable row.
   *
   * @param id message UUID
   * @param senderSub authenticated sender {@code sub}
   * @param recipientSub recipient {@code sub}
   * @param conversationId canonical id of the 1:1 conversation this message belongs to
   * @param senderDeviceId libsignal integer device number of the sender
   * @param recipientDeviceId libsignal integer device number of the recipient
   * @param messageType {@code 2} = normal SignalMessage, {@code 3} = PreKeySignalMessage
   * @param ciphertext opaque encrypted bytes, stored byte-for-byte
   */
  public EncryptedMessageEntity(
      UUID id,
      String senderSub,
      String recipientSub,
      String conversationId,
      int senderDeviceId,
      int recipientDeviceId,
      short messageType,
      byte[] ciphertext) {
    this.id = id;
    this.senderSub = senderSub;
    this.recipientSub = recipientSub;
    this.conversationId = conversationId;
    this.senderDeviceId = senderDeviceId;
    this.recipientDeviceId = recipientDeviceId;
    this.messageType = messageType;
    this.ciphertext = ciphertext;
  }

  /** Returns the message UUID. */
  public UUID getId() {
    return id;
  }

  /** Returns the authenticated sender {@code sub}. */
  public String getSenderSub() {
    return senderSub;
  }

  /** Returns the recipient {@code sub}. */
  public String getRecipientSub() {
    return recipientSub;
  }

  /** Returns the canonical id of the 1:1 conversation this message belongs to. */
  public String getConversationId() {
    return conversationId;
  }

  /** Returns the libsignal integer device number of the sender. */
  public int getSenderDeviceId() {
    return senderDeviceId;
  }

  /** Returns the libsignal integer device number of the recipient. */
  public int getRecipientDeviceId() {
    return recipientDeviceId;
  }

  /** Returns the message type discriminator ({@code 2} = SignalMessage, {@code 3} = PreKey). */
  public short getMessageType() {
    return messageType;
  }

  /** Returns the opaque ciphertext bytes. */
  public byte[] getCiphertext() {
    return ciphertext;
  }
}
