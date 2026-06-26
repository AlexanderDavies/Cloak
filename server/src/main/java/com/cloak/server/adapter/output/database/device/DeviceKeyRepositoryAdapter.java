package com.cloak.server.adapter.output.database.device;

import com.cloak.server.domain.device.DeviceKeyBundle;
import com.cloak.server.port.output.device.DeviceKeyRepositoryPort;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter implementing {@link DeviceKeyRepositoryPort}. Upserts the device row by {@code
 * (ownerSub, deviceNumber)} and replaces all prekeys on every call.
 */
@Repository
class DeviceKeyRepositoryAdapter implements DeviceKeyRepositoryPort {
  private final SpringDataDeviceRepository devices;
  private final SpringDataSignedPreKeyRepository signed;
  private final SpringDataOneTimePreKeyRepository oneTime;

  DeviceKeyRepositoryAdapter(
      SpringDataDeviceRepository devices,
      SpringDataSignedPreKeyRepository signed,
      SpringDataOneTimePreKeyRepository oneTime) {
    this.devices = devices;
    this.signed = signed;
    this.oneTime = oneTime;
  }

  @Override
  @Transactional
  public void upsert(String ownerSub, DeviceKeyBundle bundle) {
    DeviceEntity device =
        devices
            .findByOwnerSubAndDeviceNumber(ownerSub, bundle.deviceNumber())
            .orElseGet(() -> new DeviceEntity(UUID.randomUUID(), ownerSub, bundle.deviceNumber()));
    device.setIdentityKey(bundle.identityKey());
    device.setRegistrationId(bundle.registrationId());
    devices.save(device);

    signed.deleteByDeviceId(device.getId());
    oneTime.deleteByDeviceId(device.getId());
    // Flush deletes to the DB before inserting new rows so the PK constraint cannot fire
    // on the composite (device_id, key_id) if we are re-registering the same key ids.
    signed.flush();
    oneTime.flush();
    signed.save(
        new SignedPreKeyEntity(
            device.getId(),
            bundle.signedPreKey().keyId(),
            bundle.signedPreKey().publicKey(),
            bundle.signedPreKey().signature()));
    bundle
        .oneTimePreKeys()
        .forEach(
            otp ->
                oneTime.save(
                    new OneTimePreKeyEntity(device.getId(), otp.keyId(), otp.publicKey())));
  }
}
