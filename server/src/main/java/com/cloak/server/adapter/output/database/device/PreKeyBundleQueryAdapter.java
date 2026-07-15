package com.cloak.server.adapter.output.database.device;

import com.cloak.server.domain.device.KyberPreKey;
import com.cloak.server.domain.device.OneTimePreKey;
import com.cloak.server.domain.device.PreKeyBundleView;
import com.cloak.server.domain.device.SignedPreKey;
import com.cloak.server.port.output.device.PreKeyBundleQueryPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC adapter implementing {@link PreKeyBundleQueryPort}. Loads the device + signed prekey and the
 * Kyber prekey, then atomically claims one one-time prekey via {@code FOR UPDATE SKIP LOCKED}. The
 * whole method runs in one transaction so the claim's row lock is held through the UPDATE. Uses
 * {@link JdbcClient} (not JPA) to support the {@code RETURNING} clause the atomic claim needs.
 */
@Repository
class PreKeyBundleQueryAdapter implements PreKeyBundleQueryPort {

  private static final RowMapper<DeviceRow> DEVICE_MAPPER =
      (rs, n) ->
          new DeviceRow(
              rs.getObject("id", UUID.class),
              rs.getInt("registration_id"),
              rs.getInt("device_number"),
              rs.getBytes("identity_key"),
              new SignedPreKey(
                  rs.getInt("signed_key_id"),
                  rs.getBytes("signed_pub"),
                  rs.getBytes("signed_sig")));

  private static final RowMapper<KyberPreKey> KYBER_MAPPER =
      (rs, n) ->
          new KyberPreKey(rs.getInt("key_id"), rs.getBytes("public_key"), rs.getBytes("signature"));

  private static final RowMapper<OneTimePreKey> OTP_MAPPER =
      (rs, n) -> new OneTimePreKey(rs.getInt("key_id"), rs.getBytes("public_key"));

  private final JdbcClient jdbc;

  PreKeyBundleQueryAdapter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public Optional<PreKeyBundleView> fetchAndConsume(String ownerSub) {
    Optional<DeviceRow> device =
        jdbc.sql(DeviceKeyQueries.SELECT_DEVICE)
            .param("ownerSub", ownerSub)
            .query(DEVICE_MAPPER)
            .optional();
    if (device.isEmpty()) {
      return Optional.empty();
    }
    DeviceRow row = device.get();

    Optional<KyberPreKey> kyber =
        jdbc.sql(DeviceKeyQueries.SELECT_KYBER)
            .param("deviceId", row.deviceId())
            .query(KYBER_MAPPER)
            .optional();
    if (kyber.isEmpty()) {
      // A registered device must always have a Kyber prekey; a missing one is a data-integrity
      // fault. Report it as device-not-found so the caller gets a clean 404, not a 500.
      return Optional.empty();
    }

    Optional<OneTimePreKey> oneTimePreKey =
        jdbc.sql(DeviceKeyQueries.CLAIM_OTP)
            .param("deviceId", row.deviceId())
            .query(OTP_MAPPER)
            .optional();

    return Optional.of(
        new PreKeyBundleView(
            row.registrationId(),
            row.deviceNumber(),
            row.identityKey(),
            row.signedPreKey(),
            oneTimePreKey,
            kyber.get()));
  }

  private record DeviceRow(
      UUID deviceId,
      int registrationId,
      int deviceNumber,
      byte[] identityKey,
      SignedPreKey signedPreKey) {}
}
