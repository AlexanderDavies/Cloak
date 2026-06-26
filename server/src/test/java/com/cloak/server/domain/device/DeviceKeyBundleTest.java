package com.cloak.server.domain.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeviceKeyBundleTest {

  private static byte[] pub() {
    byte[] b = new byte[33];
    b[0] = 0x05;
    return b;
  }

  private static SignedPreKey signed() {
    return new SignedPreKey(1, pub(), new byte[64]);
  }

  @Test
  void buildsValidBundle() {
    var bundle =
        DeviceKeyBundle.of(12345, 1, pub(), signed(), List.of(new OneTimePreKey(1, pub())));
    assertThat(bundle.oneTimePreKeys()).hasSize(1);
    assertThat(bundle.deviceNumber()).isEqualTo(1);
    assertThat(bundle.registrationId()).isEqualTo(12345);
    assertThat(bundle.identityKey()).hasSize(33);
  }

  @Test
  void rejectsWrongIdentityKeyLength() {
    assertThatThrownBy(
            () ->
                DeviceKeyBundle.of(
                    1, 1, new byte[10], signed(), List.of(new OneTimePreKey(1, pub()))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsWrongSignatureLength() {
    assertThatThrownBy(
            () ->
                DeviceKeyBundle.of(
                    1,
                    1,
                    pub(),
                    new SignedPreKey(1, pub(), new byte[10]),
                    List.of(new OneTimePreKey(1, pub()))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDuplicateOneTimePreKeyIds() {
    var dup = List.of(new OneTimePreKey(1, pub()), new OneTimePreKey(1, pub()));
    assertThatThrownBy(() -> DeviceKeyBundle.of(1, 1, pub(), signed(), dup))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsEmptyOneTimePreKeys() {
    assertThatThrownBy(() -> DeviceKeyBundle.of(1, 1, pub(), signed(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsTooManyOneTimePreKeys() {
    List<OneTimePreKey> tooMany = new ArrayList<>();
    for (int i = 1; i <= 101; i++) {
      tooMany.add(new OneTimePreKey(i, pub()));
    }
    assertThatThrownBy(() -> DeviceKeyBundle.of(1, 1, pub(), signed(), tooMany))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
