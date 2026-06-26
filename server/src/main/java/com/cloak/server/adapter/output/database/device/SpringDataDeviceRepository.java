package com.cloak.server.adapter.output.database.device;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link DeviceEntity}. */
interface SpringDataDeviceRepository extends JpaRepository<DeviceEntity, UUID> {
  /**
   * Finds a device by its owner subject and libsignal device number.
   *
   * @param ownerSub the Keycloak subject of the owner
   * @param deviceNumber the libsignal device number
   * @return the device entity, or empty if not found
   */
  Optional<DeviceEntity> findByOwnerSubAndDeviceNumber(String ownerSub, int deviceNumber);
}
