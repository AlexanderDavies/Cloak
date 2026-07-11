package com.cloak.server.domain.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OneTimePreKeyTest {

  private static byte[] pub() {
    byte[] b = new byte[33];
    b[0] = 0x05;
    return b;
  }

  @Test
  void buildsValidOneTimePreKey() {
    var otp = new OneTimePreKey(7, pub());
    assertThat(otp.keyId()).isEqualTo(7);
    assertThat(otp.publicKey()).hasSize(33);
  }

  @Test
  void storesDefensiveCopies() {
    byte[] pub = pub();
    var otp = new OneTimePreKey(1, pub);

    pub[0] = 0x7f;
    assertThat(otp.publicKey()[0]).isEqualTo((byte) 0x05);

    otp.publicKey()[0] = 0x33;
    assertThat(otp.publicKey()[0]).isEqualTo((byte) 0x05);
  }

  @Test
  void rejectsNegativeKeyId() {
    assertThatThrownBy(() -> new OneTimePreKey(-1, pub()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("keyId");
  }

  @Test
  void rejectsNullPublicKey() {
    assertThatThrownBy(() -> new OneTimePreKey(1, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("publicKey");
  }

  @Test
  void rejectsWrongPublicKeyLength() {
    assertThatThrownBy(() -> new OneTimePreKey(1, new byte[10]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("publicKey");
  }
}
