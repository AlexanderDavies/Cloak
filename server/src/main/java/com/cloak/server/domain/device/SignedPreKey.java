package com.cloak.server.domain.device;

/** A signed prekey: id, 33-byte public key, and its 64-byte identity-key signature. */
public record SignedPreKey(int keyId, byte[] publicKey, byte[] signature) {

  /**
   * Compact constructor validating key id, public key length, and signature length.
   *
   * @param keyId non-negative key identifier
   * @param publicKey 33-byte Curve25519 public key
   * @param signature 64-byte signature over the public key bytes
   */
  public SignedPreKey {
    if (keyId < 0) {
      throw new IllegalArgumentException("keyId must be non-negative, got " + keyId);
    }
    if (publicKey == null || publicKey.length != 33) {
      throw new IllegalArgumentException(
          "publicKey must be 33 bytes, got " + (publicKey == null ? "null" : publicKey.length));
    }
    if (signature == null || signature.length != 64) {
      throw new IllegalArgumentException(
          "signature must be 64 bytes, got " + (signature == null ? "null" : signature.length));
    }
    publicKey = publicKey.clone();
    signature = signature.clone();
  }

  @Override
  public byte[] publicKey() {
    return publicKey.clone();
  }

  @Override
  public byte[] signature() {
    return signature.clone();
  }
}
