package com.cloak.server.adapter.input.rest.keys;

import com.cloak.server.domain.device.PreKeyBundleView;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Base64;

/**
 * Wire DTO for {@code GET /v1/keys/{sub}} — the recipient's public prekey bundle. Base64-encodes
 * all raw byte[] fields. {@code oneTimePreKey} is omitted from the JSON when the pool is exhausted
 * (valid no-OTP X3DH). {@code kyberPreKey} is always present. Public key material only — no private
 * keys, no ciphertext, no sub in the body (root CLAUDE.md §0.6).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PreKeyBundleResponse(
    int registrationId,
    int deviceId,
    String identityKey,
    SignedPreKeyDto signedPreKey,
    OneTimePreKeyDto oneTimePreKey,
    KyberPreKeyDto kyberPreKey) {

  /** Wire DTO for the signed prekey. */
  public record SignedPreKeyDto(int keyId, String publicKey, String signature) {}

  /** Wire DTO for a one-time prekey (absent when the pool is empty). */
  public record OneTimePreKeyDto(int keyId, String publicKey) {}

  /** Wire DTO for the last-resort Kyber prekey (always present). */
  public record KyberPreKeyDto(int keyId, String publicKey, String signature) {}

  /**
   * Maps a domain read model to the wire DTO, base64-encoding all byte[] fields.
   *
   * @param view the assembled prekey bundle view from the use case
   * @return the wire DTO ready for serialisation
   */
  public static PreKeyBundleResponse from(PreKeyBundleView view) {
    var encoder = Base64.getEncoder();
    SignedPreKeyDto signed =
        new SignedPreKeyDto(
            view.signedPreKey().keyId(),
            encoder.encodeToString(view.signedPreKey().publicKey()),
            encoder.encodeToString(view.signedPreKey().signature()));
    OneTimePreKeyDto otp =
        view.oneTimePreKey()
            .map(o -> new OneTimePreKeyDto(o.keyId(), encoder.encodeToString(o.publicKey())))
            .orElse(null);
    KyberPreKeyDto kyber =
        new KyberPreKeyDto(
            view.kyberPreKey().keyId(),
            encoder.encodeToString(view.kyberPreKey().publicKey()),
            encoder.encodeToString(view.kyberPreKey().signature()));
    return new PreKeyBundleResponse(
        view.registrationId(),
        view.deviceNumber(),
        encoder.encodeToString(view.identityKey()),
        signed,
        otp,
        kyber);
  }
}
