# Phase 0 — Message envelope (WebSocket)

The walking-skeleton message frames. The payload is an **opaque ciphertext blob**
(base64); the server never decrypts it. Real Signal sessions arrive in later slices.

## Inbound frame (client → server, over `/ws`)

```json
{
  "messageId": "uuid",
  "toSub": "string (Keycloak sub of recipient)",
  "deviceId": "uuid (sender device)",
  "ciphertext": "base64 string"
}
```

There is **no `fromSub` field** in the inbound frame. The server derives the sender identity
from the validated JWT `sub` claim on the authenticated WebSocket session
(`MessageWebSocketHandler` sets `senderSub = sub(session)`). This structurally prevents
sender spoofing — there is no client-supplied sender value to forge.

## Delivery frame (server → recipient, over `/ws`)

```json
{
  "messageId": "uuid",
  "toSub": "string (Keycloak sub of recipient)",
  "fromSub": "string (Keycloak sub of sender — server-stamped, NOT client-supplied)",
  "deviceId": "uuid (sender device)",
  "ciphertext": "base64 string"
}
```

`fromSub` is present here and is set server-side from the Avro `OutboundEnvelope`
(`server/src/main/avro/OutboundEnvelope.avsc`), which the publisher populated from the
authenticated sender `sub`. The recipient can therefore trust that `fromSub` reflects the
verified Keycloak identity of the sender.

## Cleartext-field justification (principle 6)

### Inbound frame
- `messageId` — dedupe/ordering.
- `toSub` — route/fan-out to the recipient.
- `deviceId` — multi-device targeting (later slices).
- `ciphertext` — opaque; the only content, encrypted on-device.

### Delivery frame
- `messageId` — dedupe/ordering.
- `toSub` — identifies the recipient of this delivery.
- `fromSub` — route receipts back to the sender (Slice 8); server-stamped from the JWT `sub`.
- `deviceId` — multi-device targeting (later slices).
- `ciphertext` — opaque; forwarded unchanged, never decrypted.

`fromSub` as a cleartext field is revisited when sealed-sender is considered
(future hardening); for the MVP it stays cleartext for receipt routing.

## Server trust rules

The inbound frame is supplied by an **untrusted client**.

- **Sender identity is structurally prevented from being spoofed.** The inbound frame
  carries no `fromSub` field. The server sets `sender_sub` (persisted column) and the
  delivery frame's `fromSub` exclusively from the validated Keycloak JWT `sub` claim.
  There is no client-supplied sender value to reject or rewrite — it simply does not exist
  in the inbound frame.

- **`deviceId` must belong to the authenticated sender.** A `deviceId` that is not a
  registered, non-revoked `device` row whose `owner_sub` matches the sender's `sub` must be
  rejected. **Phase 0 status: ownership validation is not yet enforced in code.** The
  `encrypted_message.device_id` column has a foreign-key constraint to `device(id)`,
  providing referential integrity, but no check verifies that the device belongs to the
  authenticated sender. Full ownership validation is pending (a later slice).

## Known gaps / open design questions

- **Recipient discovery is unspecified (design gap).** The envelope requires the sender to
  already know the recipient's Keycloak `sub` (`toSub`), but Cloak has no contact-lookup /
  user-directory mechanism. In the Phase 0 walking skeleton the recipient `sub` is known
  out-of-band (seeded test users). A real contact-discovery / delivery-addressing mechanism —
  and its privacy implications (any directory mapping human-friendly handles → `sub` is
  metadata that must be justified against root `CLAUDE.md` principle 6) — is **not covered by
  the current MVP slices** and needs a dedicated design before any user-facing release.
