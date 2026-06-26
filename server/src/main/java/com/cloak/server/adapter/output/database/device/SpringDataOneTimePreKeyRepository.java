package com.cloak.server.adapter.output.database.device;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link OneTimePreKeyEntity}. */
interface SpringDataOneTimePreKeyRepository
    extends JpaRepository<OneTimePreKeyEntity, OneTimePreKeyEntity.Key> {
  /**
   * Deletes all one-time prekeys for the given device.
   *
   * @param deviceId the owning device UUID
   */
  void deleteByDeviceId(UUID deviceId);
}
