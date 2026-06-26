package com.cloak.server.usecase;

import com.cloak.server.port.output.device.DeviceKeyRepositoryPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Component;

/** Persists a device's public key bundle (idempotent upsert). No ciphertext, no private keys. */
@Component
public class RegisterDeviceKeysUseCase {
  private final DeviceKeyRepositoryPort repository;
  private final Counter registered;

  /**
   * Creates the use case.
   *
   * @param repository the device key registry port
   * @param registry meter registry for the registration counter
   */
  public RegisterDeviceKeysUseCase(DeviceKeyRepositoryPort repository, MeterRegistry registry) {
    this.repository = repository;
    this.registered =
        Counter.builder("cloak.devices.registered")
            .description("Count of device public-key bundles registered.")
            .register(registry);
  }

  /**
   * Upserts the bundle for the authenticated owner.
   *
   * @param cmd the registration command (owner already resolved from the authenticated principal)
   */
  @Observed(name = "cloak.device.register")
  public void register(RegisterDeviceKeysCommand cmd) {
    repository.upsert(cmd.ownerSub(), cmd.bundle());
    registered.increment();
  }
}
