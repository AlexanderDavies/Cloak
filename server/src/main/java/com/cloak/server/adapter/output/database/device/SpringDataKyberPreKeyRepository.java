package com.cloak.server.adapter.output.database.device;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link KyberPreKeyEntity}. */
interface SpringDataKyberPreKeyRepository
    extends JpaRepository<KyberPreKeyEntity, KyberPreKeyEntity.Key> {
  /**
   * Deletes all Kyber prekeys for the given device.
   *
   * @param deviceId the owning device UUID
   */
  void deleteByDeviceId(UUID deviceId);
}
