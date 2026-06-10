# Phase 0 — Message envelope (WebSocket)

The walking-skeleton message frame. The payload is an **opaque ciphertext blob**
(base64); the server never decrypts it. Real Signal sessions arrive in later slices.

## Frame (client → server → recipient)

```json
{
  "messageId": "uuid",
  "toSub": "string (Keycloak sub of recipient)",
  "fromSub": "string (Keycloak sub of sender)",
  "deviceId": "uuid (sender device)",
  "ciphertext": "base64 string"
}
```

## Cleartext-field justification (principle 6)
- `messageId` — dedupe/ordering.
- `toSub` — route/fan-out to the recipient.
- `fromSub` — route receipts back to the sender (Slice 8).
- `deviceId` — multi-device targeting (later slices).
- `ciphertext` — opaque; the only content, encrypted on-device.

`fromSub` as a cleartext field is revisited when sealed-sender is considered
(future hardening); for the MVP it stays cleartext for receipt routing.

## Server trust rules

The envelope is supplied by an **untrusted client**. Cleartext routing fields must not be
taken at face value:

- **`fromSub` must equal the authenticated sender — never trust the client value.** The
  server derives the sender identity from the validated Keycloak JWT (`sub` claim), and the
  persisted `encrypted_message.sender_sub` is always set from that token `sub` — not from
  the envelope. If the envelope's `fromSub` is present and does not match the authenticated
  principal, **reject the frame** (do not silently rewrite it). This prevents a client from
  forging messages as another user (sender spoofing).
- **`deviceId` must belong to the authenticated sender.** Reject a `deviceId` that is not a
  registered, non-revoked `device` row whose `owner_sub` matches the sender's `sub`.

(Enforced in **Plan 2**, when JWT validation and the WebSocket auth handshake land — there is
no auth in the Phase 0 foundation slice yet.)

## Known gaps / open design questions

- **Recipient discovery is unspecified (design gap).** The envelope requires the sender to
  already know the recipient's Keycloak `sub` (`toSub`), but Cloak has no contact-lookup /
  user-directory mechanism. In the Phase 0 walking skeleton the recipient `sub` is known
  out-of-band (seeded test users). A real contact-discovery / delivery-addressing mechanism —
  and its privacy implications (any directory mapping human-friendly handles → `sub` is
  metadata that must be justified against root `CLAUDE.md` principle 6) — is **not covered by
  the current MVP slices** and needs a dedicated design before any user-facing release.
