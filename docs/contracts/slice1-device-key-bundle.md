# Slice 1 — Device public key bundle (REST)

Published by a device on first run so other users can later start an encrypted session with it
(Slice 2 / X3DH). The bundle is **public key material only** — private keys never leave the device.

## Request — `PUT /v1/keys`

Bearer-authenticated (`Authorization: Bearer <Keycloak access token>`, `aud: cloak-api`).
The owner is taken from the validated JWT `sub` — **never** from the body.

```json
{
  "registrationId": 12345,
  "deviceId": 1,
  "identityKey": "base64 (33 bytes)",
  "signedPreKey": { "keyId": 1, "publicKey": "base64 (33 bytes)", "signature": "base64 (64 bytes)" },
  "oneTimePreKeys": [ { "keyId": 1, "publicKey": "base64 (33 bytes)" } ]
}
```

- `registrationId` — libsignal registration id (1..16383).
- `deviceId` — libsignal device number; **1** for the primary device (multi-device = Slice 5).
- `oneTimePreKeys` — 1..100 entries, unique `keyId`s.

Idempotent: re-`PUT` replaces this device's bundle. **Response: `204 No Content`.**

## Validation (server)
Structural + auth only — shape, base64 decodes, public keys = 33 bytes, signature = 64 bytes,
1..100 one-time prekeys with unique ids. The signed-prekey signature is verified **client-side**
during X3DH (Slice 2), where the trust decision lives; the server stays content-blind.

## Cleartext justification (root CLAUDE.md principle 6)
Every field is public key material or routing identity. No plaintext content, no private keys.
