package com.cloak.server.domain.device;

/** A single one-time prekey: an id and its 33-byte Curve25519 public key. */
public record OneTimePreKey(int keyId, byte[] publicKey) {

  /**
   * Compact constructor validating key id and public key length.
   *
   * @param keyId non-negative key identifier
   * @param publicKey 33-byte Curve25519 public key
   */
  public OneTimePreKey {
    if (keyId < 0) {
      throw new IllegalArgumentException("keyId must be non-negative, got " + keyId);
    }
    if (publicKey == null || publicKey.length != 33) {
      throw new IllegalArgumentException(
          "publicKey must be 33 bytes, got " + (publicKey == null ? "null" : publicKey.length));
    }
    publicKey = publicKey.clone();
  }

  @Override
  public byte[] publicKey() {
    return publicKey.clone();
  }
}
