package com.cloak.server.adapter.input.rest.keys;

import com.cloak.server.domain.device.DeviceKeyBundle;
import com.cloak.server.domain.device.OneTimePreKey;
import com.cloak.server.domain.device.SignedPreKey;
import java.util.Base64;
import java.util.List;

/** Wire DTO for PUT /v1/keys. base64 fields decode to raw key bytes; maps to the domain bundle. */
public record PublishKeyBundleRequest(
    int registrationId,
    int deviceId,
    String identityKey,
    SignedPreKeyDto signedPreKey,
    List<OneTimePreKeyDto> oneTimePreKeys) {

  /** Wire DTO for a signed prekey. */
  public record SignedPreKeyDto(int keyId, String publicKey, String signature) {}

  /** Wire DTO for a one-time prekey. */
  public record OneTimePreKeyDto(int keyId, String publicKey) {}

  /**
   * Decodes base64 fields and validates into the domain bundle.
   *
   * @throws IllegalArgumentException on any structural violation (bad key length, etc.)
   */
  DeviceKeyBundle toDomain() {
    if (signedPreKey == null || oneTimePreKeys == null || identityKey == null) {
      throw new IllegalArgumentException("missing required fields");
    }
    var decoder = Base64.getDecoder();
    var signed =
        new SignedPreKey(
            signedPreKey.keyId(),
            decoder.decode(signedPreKey.publicKey()),
            decoder.decode(signedPreKey.signature()));
    var otps =
        oneTimePreKeys.stream()
            .map(o -> new OneTimePreKey(o.keyId(), decoder.decode(o.publicKey())))
            .toList();
    return DeviceKeyBundle.of(registrationId, deviceId, decoder.decode(identityKey), signed, otps);
  }
}
