package com.cloak.server.port.output.device;

import com.cloak.server.domain.device.DeviceKeyBundle;

/**
 * Output port: persists a device's public key bundle, replacing any prior bundle for the device.
 */
public interface DeviceKeyRepositoryPort {
  /**
   * Idempotently upserts the bundle for {@code (ownerSub, bundle.deviceNumber())}, replacing the
   * device's signed prekey and one-time prekeys.
   *
   * @param ownerSub the authenticated owner subject (from the JWT)
   * @param bundle the validated public key bundle
   */
  void upsert(String ownerSub, DeviceKeyBundle bundle);
}
