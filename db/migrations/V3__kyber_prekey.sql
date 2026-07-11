-- Slice 2 PQXDH: last-resort Kyber (ML-KEM-1024) prekey. One row per device, replaced on every
-- PUT /v1/keys. Public key material only; no plaintext (root CLAUDE.md principle 6).
CREATE TABLE kyber_prekey (
  device_id   UUID        NOT NULL REFERENCES device(id) ON DELETE CASCADE,
  key_id      INTEGER     NOT NULL,
  public_key  BYTEA       NOT NULL,
  signature   BYTEA       NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (device_id, key_id)
);
