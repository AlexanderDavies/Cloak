package com.cloak.server.adapter.output.database.device;

import com.cloak.server.domain.device.KyberPreKey;
import com.cloak.server.domain.device.OneTimePreKey;
import com.cloak.server.domain.device.PreKeyBundleView;
import com.cloak.server.domain.device.SignedPreKey;
import com.cloak.server.port.output.device.PreKeyBundleQueryPort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC adapter implementing {@link PreKeyBundleQueryPort}. Loads device, signed prekey, and Kyber
 * prekey in a single query, then atomically claims one one-time prekey via {@code FOR UPDATE SKIP
 * LOCKED} — the entire method runs in a single transaction so the row lock is held through the
 * UPDATE. Uses {@link JdbcTemplate} directly (not JPA derived methods) to support the {@code
 * RETURNING} clause needed for the atomic OTP claim.
 */
@Repository
class PreKeyBundleQueryAdapter implements PreKeyBundleQueryPort {

  private static final String SELECT_DEVICE =
      """
      SELECT d.id, d.registration_id, d.device_number, d.public_key AS identity_key,
             s.key_id AS signed_key_id, s.public_key AS signed_pub, s.signature AS signed_sig
      FROM device d
      JOIN signed_prekey s ON s.device_id = d.id
      WHERE d.owner_sub = ? AND d.device_number = 1
      """;

  private static final String SELECT_KYBER =
      """
      SELECT key_id, public_key, signature
      FROM kyber_prekey
      WHERE device_id = ?
      """;

  /**
   * Atomically claims one unconsumed OTP: marks it consumed and returns its key material. {@code
   * FOR UPDATE SKIP LOCKED} prevents two concurrent fetches from claiming the same row. Returns 0
   * rows (not an error) when the pool is empty.
   */
  private static final String CLAIM_OTP =
      """
      UPDATE one_time_prekey SET consumed_at = now()
      WHERE (device_id, key_id) IN (
        SELECT device_id, key_id FROM one_time_prekey
        WHERE device_id = ? AND consumed_at IS NULL
        ORDER BY key_id LIMIT 1
        FOR UPDATE SKIP LOCKED)
      RETURNING key_id, public_key;
      """;

  private final JdbcTemplate jdbc;

  /**
   * Creates the adapter.
   *
   * @param jdbc the JDBC template bound to the application data source
   */
  PreKeyBundleQueryAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * {@inheritDoc}
   *
   * <p>All reads and the OTP claim UPDATE run inside a single transaction so the {@code FOR UPDATE
   * SKIP LOCKED} row lock is held until commit, preventing concurrent fetches from handing out the
   * same OTP.
   */
  @Override
  @Transactional
  public Optional<PreKeyBundleView> fetchAndConsume(String ownerSub) {
    List<Map<String, Object>> deviceRows = jdbc.queryForList(SELECT_DEVICE, ownerSub);
    if (deviceRows.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Object> row = deviceRows.get(0);
    UUID deviceId = (UUID) row.get("id");
    int registrationId = (Integer) row.get("registration_id");
    int deviceNumber = (Integer) row.get("device_number");
    byte[] identityKey = (byte[]) row.get("identity_key");
    SignedPreKey signedPreKey =
        new SignedPreKey(
            (Integer) row.get("signed_key_id"),
            (byte[]) row.get("signed_pub"),
            (byte[]) row.get("signed_sig"));

    List<Map<String, Object>> kyberRows = jdbc.queryForList(SELECT_KYBER, deviceId);
    if (kyberRows.isEmpty()) {
      // Data-integrity guard: kyber prekey should always be present for a registered device.
      // Treat as device-not-found so callers receive a clean 404 rather than a 500.
      return Optional.empty();
    }
    Map<String, Object> kyberRow = kyberRows.get(0);
    KyberPreKey kyberPreKey =
        new KyberPreKey(
            (Integer) kyberRow.get("key_id"),
            (byte[]) kyberRow.get("public_key"),
            (byte[]) kyberRow.get("signature"));

    List<Map<String, Object>> otpRows = jdbc.queryForList(CLAIM_OTP, deviceId);
    OneTimePreKey oneTimePreKey = null;
    if (!otpRows.isEmpty()) {
      Map<String, Object> otpRow = otpRows.get(0);
      oneTimePreKey =
          new OneTimePreKey((Integer) otpRow.get("key_id"), (byte[]) otpRow.get("public_key"));
    }

    return Optional.of(
        new PreKeyBundleView(
            registrationId,
            deviceNumber,
            identityKey,
            signedPreKey,
            Optional.ofNullable(oneTimePreKey),
            kyberPreKey));
  }
}
