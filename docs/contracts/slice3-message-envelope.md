# Slice 3 — Message envelope (WebSocket)

Supersedes `slice2-message-envelope.md`. The Slice 3 envelope adds a deterministic
`conversationId` so the server can group messages into a conversation without ever
seeing plaintext, and gives the local (SQLCipher) history — now authoritative for
relaunch/restore — a stable grouping key.

## Changes from Slice 2

| Field | Slice 2 | Slice 3 |
|-------|---------|---------|
| `conversationId` | absent | **added** (inbound + delivery) — canonical hash of the two member subs; server-validated, frame dropped on mismatch |

No other field changes. `fromSub` remains server-stamped-only on the delivery frame,
as in Slice 2.

## Canonical conversation id

```
conversationId = lowercaseHex( SHA-256( UTF8( members.sorted().joined(separator: ":") ) ) )
```

For a 1:1 conversation, `members = [senderSub, recipientSub]`. Sort lexicographically by
Unicode scalar (ascending), join with a single `:`, SHA-256, lowercase hex. Sorting first
makes the id order-independent — either participant computes the same value regardless of
who is `sender` and who is `recipient` in a given message.

This is a pure function of the two participant `sub`s (both of which the server already
sees on every message), so both client and server can compute it independently — see
`docs/superpowers/plans/2026-07-29-slice-3-double-ratchet-history.md` (Global Constraints)
for the shared source-of-truth statement of this algorithm.

## Inbound frame (client → server, over `/ws`)

```json
{
  "messageId":      "uuid",
  "toSub":          "string (Keycloak sub of recipient)",
  "toDeviceId":     1,
  "fromDeviceId":   1,
  "messageType":    3,
  "conversationId": "string (lowercase sha256 hex, 64 chars)",
  "ciphertext":     "base64 (opaque Signal ciphertext, never decrypted by the server)"
}
```

There is still **no `fromSub` field** in the inbound frame (unchanged from Slice 2) — the
server derives the sender identity from the validated JWT `sub` claim on the authenticated
WebSocket session.

The server **validates** `conversationId` server-side: it recomputes the canonical hash from
the authenticated sender `sub` and the frame's `toSub`, and **drops the frame** (no delivery,
no persisted row) if the client-supplied value does not match. The client cannot use this
field to misroute or misgroup a message; it is checked, not trusted.

### `messageType` values

| Value | Meaning |
|-------|---------|
| `2` | `SignalMessage` — normal message in an established session. |
| `3` | `PreKeySignalMessage` — first message in a new session; triggers X3DH key agreement on the recipient's side. |

### `ciphertext` contents

`ciphertext` is base64 of the encrypted inner payload `{ sentAt, text }` (see
`app/Cloak/Foundation/Encryption/MessageContent.swift`, Task C2). `sentAt` is the
sender-authoritative send timestamp and drives display order on both ends; it never travels
as cleartext metadata. The server never decrypts `ciphertext` and has no visibility into
`sentAt` or `text`.

## Delivery frame (server → recipient, over `/ws`)

```json
{
  "messageId":      "uuid",
  "toSub":          "string (Keycloak sub of recipient)",
  "fromSub":        "string (Keycloak sub of sender — server-stamped, NOT client-supplied)",
  "toDeviceId":     1,
  "fromDeviceId":   1,
  "messageType":    3,
  "conversationId": "string (lowercase sha256 hex, 64 chars)",
  "ciphertext":     "base64 (forwarded unchanged, never decrypted by the server)"
}
```

`conversationId` is forwarded unchanged from the (now-validated) inbound frame. `fromSub`
is set server-side from the authenticated sender `sub`, unchanged from Slice 2.

## Cleartext-field justification (principle 6)

### Inbound frame

| Field | Justification |
|-------|--------------|
| `messageId` | Deduplicate and order messages on delivery. |
| `toSub` | Route / fan-out to the correct recipient. |
| `toDeviceId` | Target a specific recipient device (required for Signal multi-device). |
| `fromDeviceId` | Identify the sender's device so the recipient can locate the correct Signal session. |
| `messageType` | Select the correct decrypt path (`SignalMessage` vs `PreKeySignalMessage`). Without this the recipient cannot begin decryption. |
| `conversationId` | Group messages into a conversation for local history and the `GET /v1/messages` backlog cursor, without the server needing to see plaintext. It is a pure (deterministic, order-independent) function of `sender_sub` and `recipient_sub`, **both of which the server already observes on every routed message** — so it reveals no relationship or grouping information beyond what `toSub` + the authenticated sender already disclose. The server does not trust the client's value: it recomputes and validates it, dropping the frame on mismatch, so this field cannot be used to inject a false grouping. |
| `ciphertext` | Opaque; the only content field; encrypted on-device. |

### Delivery frame (additional field)

| Field | Justification |
|-------|--------------|
| `fromSub` | Route delivery receipts back to the sender (Slice 8); server-stamped from the JWT `sub`. |

`fromSub` and `messageType` cleartext status are revisited under future sealed-sender
hardening, as noted in `slice2-message-envelope.md`; that reasoning is unchanged by this
slice.

## Server trust rules

Unchanged from Slice 2 (sender identity structurally cannot be spoofed; `fromDeviceId`
ownership validation remains deferred), **plus**:

- **`conversationId` is validated, not trusted.** The server recomputes the canonical hash
  from the authenticated sender `sub` and the frame's `toSub` and compares it to the
  client-supplied value. On mismatch the frame is dropped: no delivery to the recipient, no
  persisted row.

## Known gaps / open design questions

Unchanged from Slice 2 — contact discovery remains outside this slice's scope (see
`slice2-message-envelope.md`).
