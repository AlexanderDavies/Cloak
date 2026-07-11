package com.cloak.server.domain.device;

import java.util.Optional;

/**
 * Read model for a device's public prekey bundle, assembled by the fetch use case for a sender
 * running X3DH. Public key material only — no private keys, no plaintext (root CLAUDE.md §0.6).
 *
 * <p>{@code oneTimePreKey} is absent when the device's pool is exhausted; X3DH proceeds without an
 * OTP in that case. {@code kyberPreKey} is always present (last-resort ML-KEM-1024 prekey).
 */
public record PreKeyBundleView(
    int registrationId,
    int deviceNumber,
    byte[] identityKey,
    SignedPreKey signedPreKey,
    Optional<OneTimePreKey> oneTimePreKey,
    KyberPreKey kyberPreKey) {}
