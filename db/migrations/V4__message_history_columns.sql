-- Slice 3: persist the real routing/decrypt fields the read-back path needs, retiring the
-- always-null device_id UUID.
--
-- Pre-release, and the ciphertext queue is disposable: decrypt-once means a delivered row can
-- never be decrypted again, so there is nothing to backfill into the new NOT NULL columns and no
-- meaningful value to preserve. Truncating first is what makes the NOT NULL adds succeed on a
-- developer database that already holds rows (they would otherwise fail with 23502).
TRUNCATE encrypted_message;

ALTER TABLE encrypted_message DROP COLUMN device_id;
ALTER TABLE encrypted_message ADD COLUMN conversation_id     TEXT     NOT NULL;
ALTER TABLE encrypted_message ADD COLUMN sender_device_id    INT      NOT NULL;
ALTER TABLE encrypted_message ADD COLUMN recipient_device_id INT      NOT NULL;
ALTER TABLE encrypted_message ADD COLUMN message_type        SMALLINT NOT NULL;

CREATE INDEX idx_message_conversation ON encrypted_message (conversation_id, created_at);
