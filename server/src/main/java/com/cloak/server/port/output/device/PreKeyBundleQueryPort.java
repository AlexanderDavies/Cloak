package com.cloak.server.port.output.device;

import com.cloak.server.domain.device.PreKeyBundleView;
import java.util.Optional;

/**
 * Output port: fetches a recipient's public prekey bundle and atomically consumes exactly one
 * one-time prekey per call.
 *
 * <p>The returned bundle is used by the sender to run X3DH. The one-time prekey, once handed out,
 * is marked consumed and never returned again. If the pool is empty the bundle is returned without
 * an OTP (valid no-OTP X3DH). An empty Optional signals no device is registered for this sub.
 */
public interface PreKeyBundleQueryPort {

  /**
   * Loads the device bundle for {@code ownerSub} (device_number=1) and atomically marks one
   * one-time prekey as consumed.
   *
   * @param ownerSub the Keycloak subject of the recipient
   * @return the bundle (with or without an OTP), or empty if no device is registered
   */
  Optional<PreKeyBundleView> fetchAndConsume(String ownerSub);
}
