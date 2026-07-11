package com.cloak.server.domain.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SignedPreKeyTest {

  private static byte[] pub() {
    byte[] b = new byte[33];
    b[0] = 0x05;
    return b;
  }

  @Test
  void buildsValidSignedPreKey() {
    var signed = new SignedPreKey(1, pub(), new byte[64]);
    assertThat(signed.keyId()).isEqualTo(1);
    assertThat(signed.publicKey()).hasSize(33);
    assertThat(signed.signature()).hasSize(64);
  }

  @Test
  void storesDefensiveCopies() {
    byte[] pub = pub();
    byte[] sig = new byte[64];
    sig[0] = 0x01;
    var signed = new SignedPreKey(1, pub, sig);

    pub[0] = 0x7f;
    sig[0] = 0x7f;
    assertThat(signed.publicKey()[0]).isEqualTo((byte) 0x05);
    assertThat(signed.signature()[0]).isEqualTo((byte) 0x01);

    signed.publicKey()[0] = 0x33;
    assertThat(signed.publicKey()[0]).isEqualTo((byte) 0x05);
  }

  @Test
  void rejectsNegativeKeyId() {
    assertThatThrownBy(() -> new SignedPreKey(-1, pub(), new byte[64]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("keyId");
  }

  @Test
  void rejectsNullPublicKey() {
    assertThatThrownBy(() -> new SignedPreKey(1, null, new byte[64]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("publicKey");
  }

  @Test
  void rejectsWrongPublicKeyLength() {
    assertThatThrownBy(() -> new SignedPreKey(1, new byte[10], new byte[64]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("publicKey");
  }

  @Test
  void rejectsNullSignature() {
    assertThatThrownBy(() -> new SignedPreKey(1, pub(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("signature");
  }

  @Test
  void rejectsWrongSignatureLength() {
    assertThatThrownBy(() -> new SignedPreKey(1, pub(), new byte[10]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("signature");
  }
}
