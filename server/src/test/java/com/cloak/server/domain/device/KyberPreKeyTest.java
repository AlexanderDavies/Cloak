package com.cloak.server.domain.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KyberPreKeyTest {

  private static byte[] validPublicKey() {
    return new byte[KyberPreKey.KEM_PUBLIC_KEY_LENGTH];
  }

  private static byte[] validSignature() {
    return new byte[64];
  }

  @Test
  void buildsValidKyberPreKey() {
    var kyber = new KyberPreKey(1, validPublicKey(), validSignature());
    assertThat(kyber.keyId()).isEqualTo(1);
    assertThat(kyber.publicKey()).hasSize(KyberPreKey.KEM_PUBLIC_KEY_LENGTH);
    assertThat(kyber.signature()).hasSize(64);
  }

  @Test
  void storesDefensiveCopies() {
    byte[] pub = validPublicKey();
    byte[] sig = validSignature();
    pub[0] = 0x01;
    sig[0] = 0x02;
    var kyber = new KyberPreKey(1, pub, sig);

    // Mutating the source arrays after construction must not affect the stored values.
    pub[0] = 0x7f;
    sig[0] = 0x7f;
    assertThat(kyber.publicKey()[0]).isEqualTo((byte) 0x01);
    assertThat(kyber.signature()[0]).isEqualTo((byte) 0x02);

    // Mutating a returned array must not affect the next read either.
    kyber.publicKey()[0] = 0x33;
    assertThat(kyber.publicKey()[0]).isEqualTo((byte) 0x01);
  }

  @Test
  void rejectsNegativeKeyId() {
    assertThatThrownBy(() -> new KyberPreKey(-1, validPublicKey(), validSignature()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("keyId");
  }

  @Test
  void rejectsNullPublicKey() {
    assertThatThrownBy(() -> new KyberPreKey(1, null, validSignature()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("publicKey");
  }

  @Test
  void rejectsWrongPublicKeyLength() {
    assertThatThrownBy(() -> new KyberPreKey(1, new byte[10], validSignature()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("publicKey");
  }

  @Test
  void rejectsNullSignature() {
    assertThatThrownBy(() -> new KyberPreKey(1, validPublicKey(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("signature");
  }

  @Test
  void rejectsWrongSignatureLength() {
    assertThatThrownBy(() -> new KyberPreKey(1, validPublicKey(), new byte[10]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("signature");
  }
}
