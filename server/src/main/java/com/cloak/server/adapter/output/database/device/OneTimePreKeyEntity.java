package com.cloak.server.adapter.output.database.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** JPA row for {@code one_time_prekey} (composite key device_id + key_id). */
@Entity
@Table(name = "one_time_prekey")
@IdClass(OneTimePreKeyEntity.Key.class)
class OneTimePreKeyEntity {
  @Id
  @Column(name = "device_id")
  private UUID deviceId;

  @Id
  @Column(name = "key_id")
  private int keyId;

  @Column(name = "public_key", nullable = false)
  private byte[] publicKey;

  @Column(name = "consumed_at")
  private OffsetDateTime consumedAt;

  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  /** JPA-required no-args constructor. */
  protected OneTimePreKeyEntity() {}

  /**
   * Builds a persistable one-time prekey row with {@code consumed_at} null (available for X3DH).
   *
   * @param deviceId the owning device UUID
   * @param keyId the libsignal key id
   * @param publicKey 33-byte Curve25519 public key
   */
  OneTimePreKeyEntity(UUID deviceId, int keyId, byte[] publicKey) {
    this.deviceId = deviceId;
    this.keyId = keyId;
    this.publicKey = publicKey;
  }

  /** Composite key class for {@link OneTimePreKeyEntity}. */
  static class Key implements Serializable {
    private UUID deviceId;
    private int keyId;

    /** JPA-required no-args constructor. */
    public Key() {}

    /**
     * Builds a composite key.
     *
     * @param deviceId the owning device UUID
     * @param keyId the libsignal key id
     */
    public Key(UUID deviceId, int keyId) {
      this.deviceId = deviceId;
      this.keyId = keyId;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Key k)) {
        return false;
      }
      return keyId == k.keyId && Objects.equals(deviceId, k.deviceId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(deviceId, keyId);
    }
  }
}
