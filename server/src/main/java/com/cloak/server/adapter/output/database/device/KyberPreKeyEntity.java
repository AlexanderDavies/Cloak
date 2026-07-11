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

/** JPA row for {@code kyber_prekey} (composite key device_id + key_id). */
@Entity
@Table(name = "kyber_prekey")
@IdClass(KyberPreKeyEntity.Key.class)
class KyberPreKeyEntity {
  @Id
  @Column(name = "device_id")
  private UUID deviceId;

  @Id
  @Column(name = "key_id")
  private int keyId;

  @Column(name = "public_key", nullable = false)
  private byte[] publicKey;

  @Column(name = "signature", nullable = false)
  private byte[] signature;

  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  /** JPA-required no-args constructor. */
  protected KyberPreKeyEntity() {}

  /**
   * Builds a persistable Kyber prekey row.
   *
   * @param deviceId the owning device UUID
   * @param keyId the libsignal key id
   * @param publicKey 1569-byte ML-KEM-1024 encapsulation public key (1568 raw + 1 type tag)
   * @param signature 64-byte XEdDSA signature over the public key bytes
   */
  KyberPreKeyEntity(UUID deviceId, int keyId, byte[] publicKey, byte[] signature) {
    this.deviceId = deviceId;
    this.keyId = keyId;
    this.publicKey = publicKey;
    this.signature = signature;
  }

  /** Composite key class for {@link KyberPreKeyEntity}. */
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
