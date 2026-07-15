package com.cloak.server.adapter.output.database.device;

/** SQL for {@link PreKeyBundleQueryAdapter}, grouped here so the adapter stays mapping logic. */
final class DeviceKeyQueries {

  private DeviceKeyQueries() {}

  static final String SELECT_DEVICE =
      """
      SELECT d.id, d.registration_id, d.device_number, d.public_key AS identity_key,
             s.key_id AS signed_key_id, s.public_key AS signed_pub, s.signature AS signed_sig
      FROM device d
      JOIN signed_prekey s ON s.device_id = d.id
      WHERE d.owner_sub = :ownerSub AND d.device_number = 1
      """;

  static final String SELECT_KYBER =
      """
      SELECT key_id, public_key, signature
      FROM kyber_prekey
      WHERE device_id = :deviceId
      """;

  /**
   * Atomically claims one unconsumed OTP: marks it consumed and returns its key material. {@code
   * FOR UPDATE SKIP LOCKED} stops two concurrent fetches from claiming the same row; the enclosing
   * transaction holds the lock until commit. Returns 0 rows (not an error) when the pool is empty.
   */
  static final String CLAIM_OTP =
      """
      UPDATE one_time_prekey SET consumed_at = now()
      WHERE (device_id, key_id) IN (
        SELECT device_id, key_id FROM one_time_prekey
        WHERE device_id = :deviceId AND consumed_at IS NULL
        ORDER BY key_id LIMIT 1
        FOR UPDATE SKIP LOCKED)
      RETURNING key_id, public_key;
      """;
}
