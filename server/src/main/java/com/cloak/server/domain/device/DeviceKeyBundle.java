package com.cloak.server.domain.device;

import java.util.List;

/**
 * A device's public prekey bundle. Validates structure; never crypto-verifies (client does, Slice
 * 2).
 */
public record DeviceKeyBundle(
    int registrationId,
    int deviceNumber,
    byte[] identityKey,
    SignedPreKey signedPreKey,
    KyberPreKey kyberPreKey,
    List<OneTimePreKey> oneTimePreKeys) {

  private static final int MAX_ONE_TIME_PREKEYS = 100;

  /** Compact constructor: validates all fields and defensively copies mutable inputs. */
  public DeviceKeyBundle {
    if (registrationId <= 0 || registrationId > 0x3FFF) {
      throw new IllegalArgumentException("registrationId must be 1..16383, got " + registrationId);
    }
    if (deviceNumber < 1) {
      throw new IllegalArgumentException("deviceNumber must be >= 1, got " + deviceNumber);
    }
    if (identityKey == null || identityKey.length != 33) {
      throw new IllegalArgumentException(
          "identityKey must be 33 bytes, got "
              + (identityKey == null ? "null" : identityKey.length));
    }
    if (signedPreKey == null) {
      throw new IllegalArgumentException("signedPreKey required");
    }
    if (kyberPreKey == null) {
      throw new IllegalArgumentException("kyberPreKey required");
    }
    if (oneTimePreKeys == null
        || oneTimePreKeys.isEmpty()
        || oneTimePreKeys.size() > MAX_ONE_TIME_PREKEYS) {
      throw new IllegalArgumentException(
          "oneTimePreKeys must be 1.."
              + MAX_ONE_TIME_PREKEYS
              + ", got "
              + (oneTimePreKeys == null ? "null" : oneTimePreKeys.size()));
    }
    // List.copyOf rejects null elements, giving a clean NullPointerException instead of
    // propagating nulls into the uniqueness stream.
    List<OneTimePreKey> safeKeys = List.copyOf(oneTimePreKeys);
    long distinct = safeKeys.stream().map(OneTimePreKey::keyId).distinct().count();
    if (distinct != safeKeys.size()) {
      throw new IllegalArgumentException("oneTimePreKey ids must be unique");
    }
    identityKey = identityKey.clone();
    oneTimePreKeys = safeKeys;
  }

  /**
   * Validates and builds a bundle.
   *
   * @param registrationId libsignal registration id (1..16383)
   * @param deviceNumber libsignal device number (&gt;= 1)
   * @param identityKey 33-byte identity public key
   * @param signedPreKey the signed prekey
   * @param kyberPreKey the last-resort ML-KEM-1024 Kyber prekey
   * @param oneTimePreKeys 1..100 one-time prekeys with unique ids
   * @return the validated bundle
   * @throws IllegalArgumentException on any structural violation
   */
  public static DeviceKeyBundle of(
      int registrationId,
      int deviceNumber,
      byte[] identityKey,
      SignedPreKey signedPreKey,
      KyberPreKey kyberPreKey,
      List<OneTimePreKey> oneTimePreKeys) {
    return new DeviceKeyBundle(
        registrationId, deviceNumber, identityKey, signedPreKey, kyberPreKey, oneTimePreKeys);
  }

  @Override
  public byte[] identityKey() {
    return identityKey.clone();
  }
}
