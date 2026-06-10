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

  @Column(name = "device_id")
  private UUID deviceId;

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
   * @param deviceId sender device id, or {@code null}
   * @param ciphertext opaque encrypted bytes, stored byte-for-byte
   */
  public EncryptedMessageEntity(
      UUID id, String senderSub, String recipientSub, UUID deviceId, byte[] ciphertext) {
    this.id = id;
    this.senderSub = senderSub;
    this.recipientSub = recipientSub;
    this.deviceId = deviceId;
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

  /** Returns the sender device id, or {@code null}. */
  public UUID getDeviceId() {
    return deviceId;
  }

  /** Returns the opaque ciphertext bytes. */
  public byte[] getCiphertext() {
    return ciphertext;
  }
}
