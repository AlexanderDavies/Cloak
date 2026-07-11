package com.cloak.server.domain.device;

/**
 * A last-resort Kyber (ML-KEM-1024) prekey: id, 1569-byte encapsulation public key, and its 64-byte
 * identity-key XEdDSA signature. One per device; replaced on every bundle publish.
 *
 * <p>Task CK confirmed: libsignal's {@code KEMPublicKey.serialize()} prepends a 1-byte type tag, so
 * the serialised length is 1568 (raw ML-KEM-1024) + 1 (tag) = 1569 bytes.
 */
public record KyberPreKey(int keyId, byte[] publicKey, byte[] signature) {

  /**
   * Expected byte length of a serialised ML-KEM-1024 encapsulation key as produced by libsignal's
   * {@code KEMPublicKey.serialize()}: 1568 raw bytes + 1 type-tag byte = 1569.
   */
  public static final int KEM_PUBLIC_KEY_LENGTH = 1569;

  /** Expected byte length of an XEdDSA identity-key signature (same as the signed prekey). */
  private static final int SIGNATURE_LENGTH = 64;

  /**
   * Compact constructor validating key id, public key length, and signature length.
   *
   * @param keyId non-negative key identifier
   * @param publicKey 1569-byte ML-KEM-1024 encapsulation public key (1568 raw + 1 type tag)
   * @param signature 64-byte XEdDSA signature over the public key bytes
   */
  public KyberPreKey {
    if (keyId < 0) {
      throw new IllegalArgumentException("keyId must be non-negative, got " + keyId);
    }
    if (publicKey == null || publicKey.length != KEM_PUBLIC_KEY_LENGTH) {
      throw new IllegalArgumentException(
          "publicKey must be "
              + KEM_PUBLIC_KEY_LENGTH
              + " bytes, got "
              + (publicKey == null ? "null" : publicKey.length));
    }
    if (signature == null || signature.length != SIGNATURE_LENGTH) {
      throw new IllegalArgumentException(
          "signature must be "
              + SIGNATURE_LENGTH
              + " bytes, got "
              + (signature == null ? "null" : signature.length));
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
