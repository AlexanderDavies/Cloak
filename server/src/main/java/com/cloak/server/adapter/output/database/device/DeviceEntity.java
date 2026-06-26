package com.cloak.server.adapter.output.database.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** JPA row for {@code device}: routing identity plus the Curve25519 identity public key. */
@Entity
@Table(name = "device")
class DeviceEntity {
  @Id private UUID id;

  @Column(name = "owner_sub", nullable = false)
  private String ownerSub;

  @Column(name = "public_key", nullable = false)
  private byte[] publicKey;

  @Column(name = "algorithm", nullable = false)
  private String algorithm;

  @Column(name = "registration_id")
  private Integer registrationId;

  @Column(name = "device_number", nullable = false)
  private int deviceNumber;

  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "revoked_at")
  private OffsetDateTime revokedAt;

  /** JPA-required no-args constructor. */
  protected DeviceEntity() {}

  /**
   * Builds a new device row. Sets {@code algorithm} to {@code "CURVE25519"}; identity key must be
   * supplied separately via {@link #setIdentityKey(byte[])}.
   *
   * @param id device UUID
   * @param ownerSub authenticated owner subject (Keycloak {@code sub})
   * @param deviceNumber libsignal device number (&gt;= 1)
   */
  DeviceEntity(UUID id, String ownerSub, int deviceNumber) {
    this.id = id;
    this.ownerSub = ownerSub;
    this.deviceNumber = deviceNumber;
    this.algorithm = "CURVE25519";
  }

  /** Returns the device UUID. */
  UUID getId() {
    return id;
  }

  /**
   * Sets the 33-byte Curve25519 identity public key, stored in the {@code public_key} column.
   *
   * @param identityKey the raw public key bytes
   */
  void setIdentityKey(byte[] identityKey) {
    this.publicKey = identityKey;
  }

  /**
   * Sets the libsignal registration id.
   *
   * @param registrationId the registration id (1..16383)
   */
  void setRegistrationId(int registrationId) {
    this.registrationId = registrationId;
  }
}
