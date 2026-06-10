-- Cloak baseline schema. Ciphertext only; users referenced by Keycloak `sub`.
-- Cleartext columns are routing/delivery metadata only (root CLAUDE.md principle 6).

CREATE TABLE device (
    id          UUID PRIMARY KEY,
    owner_sub   TEXT        NOT NULL,
    public_key  BYTEA       NOT NULL UNIQUE,   -- a public key registers to at most one device
    algorithm   TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ
);

CREATE INDEX idx_device_owner ON device (owner_sub);

CREATE TABLE encrypted_message (
    id              UUID        PRIMARY KEY,
    sender_sub      TEXT        NOT NULL,   -- needed to route receipts back
    recipient_sub   TEXT        NOT NULL,   -- needed to route/fan-out delivery
    device_id       UUID        REFERENCES device (id),  -- target device (multi-device, later)
    ciphertext      BYTEA       NOT NULL,    -- opaque; server never decrypts
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_message_recipient ON encrypted_message (recipient_sub, created_at);
