-- Slice 1: Signal Protocol public prekey registry. Public key material + routing identity only
-- (root CLAUDE.md principle 6). device.public_key (V1) is reused as the identity public key.

ALTER TABLE device
  ADD COLUMN registration_id INTEGER,
  ADD COLUMN device_number   INTEGER NOT NULL DEFAULT 1;   -- libsignal deviceId; multi-device = Slice 5

-- Idempotent upsert key: one bundle per (user, device number).
ALTER TABLE device
  ADD CONSTRAINT uq_device_owner_number UNIQUE (owner_sub, device_number);

CREATE TABLE signed_prekey (
  device_id   UUID        NOT NULL REFERENCES device (id) ON DELETE CASCADE,
  key_id      INTEGER     NOT NULL,
  public_key  BYTEA       NOT NULL,
  signature   BYTEA       NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (device_id, key_id)
);

CREATE TABLE one_time_prekey (
  device_id   UUID        NOT NULL REFERENCES device (id) ON DELETE CASCADE,
  key_id      INTEGER     NOT NULL,
  public_key  BYTEA       NOT NULL,
  consumed_at TIMESTAMPTZ,                                  -- NULL = available; Slice 2 consumes one per X3DH
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (device_id, key_id)
);

CREATE INDEX idx_otp_available ON one_time_prekey (device_id) WHERE consumed_at IS NULL;
