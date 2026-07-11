# Slice 2 — Message envelope (WebSocket)

Supersedes `phase0-message-envelope.md`. The Slice 2 envelope carries real Signal
ciphertext (normal `SignalMessage` or `PreKeySignalMessage`) and uses integer device
numbers in place of the Phase 0 UUID `deviceId`.

## Changes from Phase 0

| Field | Phase 0 | Slice 2 |
|-------|---------|---------|
| `deviceId` (UUID) | single sender device field | **removed** |
| `toDeviceId` | absent | **integer** — recipient device number |
| `fromDeviceId` | absent | **integer** — sender device number |
| `messageType` | absent | **integer** — selects the decrypt path |

Integer device numbers match the libsignal `deviceId` convention (primary device = `1`)
and remove the UUID indirection that was a Phase 0 placeholder.

## Inbound frame (client → server, over `/ws`)

```json
{
  "messageId": "uuid",
  "toSub":      "string (Keycloak sub of recipient)",
  "toDeviceId":   1,
  "fromDeviceId": 1,
  "messageType":  3,
  "ciphertext": "base64 (opaque Signal ciphertext, never decrypted by the server)"
}
```

There is **no `fromSub` field** in the inbound frame. The server derives the sender
identity from the validated JWT `sub` claim on the authenticated WebSocket session.
This structurally prevents sender spoofing — there is no client-supplied sender value
to forge.

### `messageType` values

| Value | Meaning |
|-------|---------|
| `2` | `SignalMessage` — normal message in an established session. |
| `3` | `PreKeySignalMessage` — first message in a new session; triggers X3DH key agreement on the recipient's side. |

## Delivery frame (server → recipient, over `/ws`)

```json
{
  "messageId":    "uuid",
  "toSub":        "string (Keycloak sub of recipient)",
  "fromSub":      "string (Keycloak sub of sender — server-stamped, NOT client-supplied)",
  "toDeviceId":   1,
  "fromDeviceId": 1,
  "messageType":  3,
  "ciphertext":   "base64 (forwarded unchanged, never decrypted by the server)"
}
```

`fromSub` is present here and is set server-side from the authenticated sender `sub`
(populated into the Avro `OutboundEnvelope` by the WebSocket handler before publishing
to Kafka). The recipient can therefore trust that `fromSub` reflects the verified
Keycloak identity of the sender.

## Cleartext-field justification (principle 6)

### Inbound frame

| Field | Justification |
|-------|--------------|
| `messageId` | Deduplicate and order messages on delivery. |
| `toSub` | Route / fan-out to the correct recipient. |
| `toDeviceId` | Target a specific recipient device (required for Signal multi-device). |
| `fromDeviceId` | Identify the sender's device so the recipient can locate the correct Signal session. |
| `messageType` | Select the correct decrypt path (`SignalMessage` vs `PreKeySignalMessage`). Without this the recipient cannot begin decryption. |
| `ciphertext` | Opaque; the only content field; encrypted on-device. |

### Delivery frame (additional field)

| Field | Justification |
|-------|--------------|
| `fromSub` | Route delivery receipts back to the sender (Slice 8); server-stamped from the JWT `sub`. |

`fromSub` as a cleartext field is revisited when sealed-sender is considered (future
hardening); for the MVP it stays cleartext for receipt routing.

`messageType` is similarly revisited under sealed-sender — if the type is folded into
the ciphertext envelope, it can be removed from cleartext. For the MVP it stays cleartext
because the decrypt path must be selected before decryption begins.

## Server trust rules

The inbound frame is supplied by an **untrusted client**.

- **Sender identity is structurally prevented from being spoofed.** The inbound frame
  carries no `fromSub` field. The server sets `sender_sub` (persisted column) and the
  delivery frame's `fromSub` exclusively from the validated Keycloak JWT `sub` claim.
  There is no client-supplied sender value to reject or rewrite — it simply does not exist
  in the inbound frame.

- **`fromDeviceId` ownership validation is NOT yet enforced (deferred).** Ideally a
  `fromDeviceId` that is not a registered, non-revoked `device` row whose `owner_sub` matches
  the sender's `sub` would be rejected. **Slice 2 does not implement this check** — the
  client-supplied `fromDeviceId` flows into the routed message unvalidated. Impact this slice is
  bounded: `senderSub` is always the authenticated JWT `sub` (spoofing another *user* is
  structurally impossible), so a forged `fromDeviceId` can only misattribute among the sender's
  own devices, and `fromDeviceId` is always `1` (multi-device is Slice 5). Full ownership
  validation is deferred to a later slice (multi-device / Slice 9 hardening).

## Known gaps / open design questions

- **Contact discovery is unspecified (design gap).** The envelope requires the sender to
  already know the recipient's Keycloak `sub` (`toSub`). Slice 2 adds `GET /v1/users/lookup`
  as a minimal exact-match lookup, but a full contact-discovery mechanism — and its privacy
  implications (any directory mapping human-friendly handles → `sub` is metadata that must be
  justified against root `CLAUDE.md` principle 6) — is **not covered by the current MVP
  slices** and needs a dedicated design before any user-facing release.
