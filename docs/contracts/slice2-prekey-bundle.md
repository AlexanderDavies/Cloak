# Slice 2 — Prekey bundle fetch (REST)

Fetches the public key bundle for a remote device so the caller can open an X3DH
(Extended Triple Diffie-Hellman) / PQXDH session with it. The bundle includes a
Kyber post-quantum key (ML-KEM-1024) for PQXDH.

The bundle is **public key material only** — private keys never leave the device.

## Request — `GET /v1/keys/{sub}`

Bearer-authenticated (`Authorization: Bearer <Keycloak access token>`, `aud: cloak-api`).
`sub` is the Keycloak subject identifier of the target user (obtained from
`GET /v1/users/lookup`).

## Response — `200 OK`

```json
{
  "registrationId": 12345,
  "deviceId": 1,
  "identityKey": "base64 (33 bytes — EC public key)",
  "signedPreKey": {
    "keyId": 1,
    "publicKey": "base64 (33 bytes)",
    "signature": "base64 (64 bytes)"
  },
  "oneTimePreKey": {
    "keyId": 1,
    "publicKey": "base64 (33 bytes)"
  },
  "kyberPreKey": {
    "keyId": 1,
    "publicKey": "base64 (~2092 chars — serialised ML-KEM-1024 public key, 1568 bytes)",
    "signature": "base64 (64 bytes)"
  }
}
```

### Field rules

- `oneTimePreKey` is **omitted** when the supply is exhausted (no remaining one-time prekeys
  for this device). Callers must handle the absent field and proceed with a no-OTP X3DH
  handshake. This is a valid Signal protocol state.
- `kyberPreKey` is the device's **last-resort** Kyber key and is **always present**. It is
  **never consumed** on fetch — the same `kyberPreKey` is returned on every request until the
  device uploads a new one. Callers must still handle absent `oneTimePreKey`.
- The signed-prekey `signature` is verified **client-side** during X3DH where the trust
  decision lives; the server stores and forwards it without inspecting it.
- All keys are Curve25519 (33 bytes, Signal encoding: one-byte type prefix `0x05` + 32-byte
  public key) except `kyberPreKey.publicKey`, which is the serialised ML-KEM-1024 public
  key: **1569 bytes** (1568 raw ML-KEM-1024 + a 1-byte libsignal type tag).

## Error responses

| Status | Meaning |
|--------|---------|
| `404 Not Found` | The `sub` has no registered device bundle. |

## Server behaviour

- Returns the primary device bundle (`deviceId = 1`; multi-device targeting added in Slice 5).
- Fetch **atomically consumes** the returned one-time prekey:
  `SELECT … FOR UPDATE SKIP LOCKED` + delete in the same transaction, preventing double-use
  under concurrent requests for the same bundle.
- `kyberPreKey` is read-only on fetch (never deleted) — it is the last-resort fallback.

## Cleartext justification (root CLAUDE.md principle 6)

Every field is public key material or routing identity required to open an encrypted session:

- `registrationId`, `deviceId` — libsignal session identity, required to initialise a Signal
  session on the caller's device.
- `identityKey`, `signedPreKey`, `oneTimePreKey`, `kyberPreKey` — public key material only.
  Exposing public keys is the explicit purpose of this endpoint — without them the caller
  cannot derive the shared secret and encrypt.

No plaintext content, no private keys, no contact or relationship metadata.

## Note on kyberPreKey.publicKey fixture value

The `kyberPreKey.publicKey` in `fixtures/slice2-prekey-bundle.json` is a placeholder
(base64 of 1568 zero bytes). **The real serialised `KEMPublicKey` value is pinned during
Task CK** (the iOS Kyber task), where a genuine ML-KEM-1024 key pair is generated and its
serialised public key is substituted into the fixture.

## Note on the publish contract

The `PUT /v1/keys` publish contract (`slice1-device-key-bundle.md`) gains the same
`kyberPreKey` object (`{ keyId, publicKey, signature }`). That amendment is made in
**Task BK** alongside the server handler change; do not edit `slice1-device-key-bundle.md`
here.
