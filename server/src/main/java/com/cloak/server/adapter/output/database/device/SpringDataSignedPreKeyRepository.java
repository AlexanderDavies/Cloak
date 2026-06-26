package com.cloak.server.adapter.output.database.device;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link SignedPreKeyEntity}. */
interface SpringDataSignedPreKeyRepository
    extends JpaRepository<SignedPreKeyEntity, SignedPreKeyEntity.Key> {
  /**
   * Deletes all signed prekeys for the given device.
   *
   * @param deviceId the owning device UUID
   */
  void deleteByDeviceId(UUID deviceId);
}
