package com.cloak.server.usecase;

import com.cloak.server.domain.device.PreKeyBundleView;
import com.cloak.server.port.output.device.PreKeyBundleQueryPort;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Component;

/**
 * Fetches a recipient's public prekey bundle so a sender can run X3DH. Exactly one one-time prekey
 * is consumed atomically per call (when available). The owner sub is never logged or used as a
 * metric label (privacy — mirrors {@code cloak.messages.routed}, root §0.6).
 */
@Component
public class FetchPreKeyBundleUseCase {

  private final PreKeyBundleQueryPort queryPort;

  /**
   * Creates the use case.
   *
   * @param queryPort the prekey-bundle query port (fetch + atomic OTP consumption)
   */
  FetchPreKeyBundleUseCase(PreKeyBundleQueryPort queryPort) {
    this.queryPort = queryPort;
  }

  /**
   * Fetches the prekey bundle for the given owner sub, consuming one OTP atomically.
   *
   * @param ownerSub the Keycloak subject of the recipient device owner
   * @return the bundle (with or without an OTP if the pool is exhausted)
   * @throws DeviceNotFoundException when no device is registered for {@code ownerSub}
   */
  @Observed(name = "cloak.keys.fetch")
  public PreKeyBundleView fetch(String ownerSub) {
    return queryPort.fetchAndConsume(ownerSub).orElseThrow(DeviceNotFoundException::new);
  }
}
