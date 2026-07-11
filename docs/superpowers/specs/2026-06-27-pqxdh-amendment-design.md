# PQXDH Amendment — mandatory Kyber prekey — Design

- **Date:** 2026-06-27
- **Status:** Approved (design); pending plan refresh
- **Branch:** `feature/slice-2-x3dh` (same branch as Slice 2)
- **Amends:** `2026-06-20-slice-1-onboarding-keys-design.md` (the published device key bundle) and
  `2026-06-27-slice-2-x3dh-design.md` (X3DH / fetch / crypto)
- **Trigger:** Slice 2 plan **Task 0** (libsignal verification spike) proved **Kyber is mandatory** in
  LibSignalClient 0.96.2 — both `PreKeyBundle` initializers require `kyberPrekeyId:kyberPrekey:kyberPrekeySignature:`
  and there is no no-Kyber overload. Verified API facts: `.superpowers/sdd/task-0-report.md`.

## Goal

Make Cloak's prekey bundle PQXDH-capable so libsignal's `PreKeyBundle` / `processPreKeyBundle` work at
all. Add a **last-resort signed Kyber (ML-KEM-1024) prekey** end-to-end: generated on-device, published in
the public bundle, stored in the registry, and returned at fetch — exactly mirroring the existing signed
prekey. Also fold the spike's verified 0.96.2 API-signature corrections into the Slice 2 implementation.

This is a **pre-release amendment with no production data**: the Kyber prekey is **mandatory** in the
bundle (no back-compat). The two Slice-1 test users simply re-register — Slice 1's `DeviceRegistrationService`
is already idempotent/resumable.

## Background: what PQXDH protects, and why last-resort

PQXDH adds an ML-KEM encapsulation against the recipient's Kyber prekey and mixes the KEM secret into the
initial root key. This defends the **initial handshake secret** against a *harvest-now-decrypt-later*
adversary (recording ciphertext now to break with a future quantum computer). The subsequent Double Ratchet
remains classical ECDH — PQ-protecting the ongoing ratchet is a separate future effort, out of scope here.

**Decision (locked): last-resort signed Kyber prekey now; one-time Kyber prekeys + rotation → Slice 9.**

| Model | PQ forward secrecy of the initial secret | Effort | Slice 9 fit |
|-------|------------------------------------------|--------|-------------|
| **Last-resort (chosen)** | One reusable Kyber key/device; blocks the pure harvest-now adversary. Narrow residual exposure only if a CRQC **and** the long-lived Kyber private key are both compromised before rotation. | Clone of `signed_prekey`; no consumption. | Slice 9 adds one-time Kyber + rotation/replenishment **additively**. |
| One-time pool | Per-session PQ forward secrecy. | Pool + consumable table + atomic claim + exhaustion fallback (and still needs last-resort). | Replenishment that keeps a pool viable **is** Slice 9 — building the pool now drains to fallback until then. |

Rationale: a one-time Kyber pool depletes, and its replenishment lives in Slice 9 — so building the pool
now pays full complexity without the sustained benefit. Last-resort is valid PQXDH (Signal ships it as the
fallback) and is the consistent choice given one-time **EC** prekey replenishment is already deferred to
Slice 9. The bundle contract is shaped so Slice 9's one-time Kyber addition is purely additive.

## The contract change

Both the Slice 1 publish bundle and the Slice 2 fetch bundle gain one object, **always present** (the
last-resort key is never consumed):

```json
"kyberPreKey": {
  "keyId": 1,
  "publicKey": "base64 (serialized libsignal KEMPublicKey, ML-KEM-1024)",
  "signature": "base64 (identity-key signature over the serialized kyber public key)"
}
```

- **Signed by the identity key** — same trust pattern as the signed prekey; the receiver verifies this
  signature **client-side** during X3DH (`processPreKeyBundle` does it), so the server stays content-blind.
- **Server validation is structural only:** base64 decodes; `publicKey` length equals the libsignal
  serialized `KEMPublicKey` length; `signature` length equals the identity-signature length. **Exact byte
  lengths are pinned during implementation** (as Slice 1 pinned EC pubkey = 33, signature = 64) rather than
  guessed here — the implementer reads the lengths from a generated key and asserts them.
- **Cleartext justification (principle 6):** public key material only; the private Kyber key never leaves
  the device. No new routing/metadata fields.

Contract docs to update: `docs/contracts/slice1-device-key-bundle.md` (publish) and
`docs/contracts/slice2-prekey-bundle.md` (fetch) + their JSON fixtures.

## DB — Flyway `V3`

A migration **is** required after all (the Slice 2 spec's "no migration" applied only to the message
envelope; this amendment adds key storage). New table mirroring `signed_prekey`:

```sql
CREATE TABLE kyber_prekey (
  device_id   UUID        NOT NULL REFERENCES device(id) ON DELETE CASCADE,
  key_id      INTEGER     NOT NULL,
  public_key  BYTEA       NOT NULL,
  signature   BYTEA       NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (device_id, key_id)
);
```

One row per device, replaced on re-`PUT` (same upsert path that already replaces the signed prekey). No
`consumed_at` column — the last-resort key is reusable. (Slice 9 will add a one-time Kyber table with
`consumed_at` + replenishment; this `kyber_prekey` table becomes the last-resort fallback.)

## Server (extends Slice 1 + Slice 2 work)

- **Slice 1 path:** `domain/device/DeviceKeyBundle` + a new `domain/device/KyberPreKey` value object;
  `PublishKeyBundleRequest` gains the `kyberPreKey` DTO; `RegisterDeviceKeysUseCase` validates it
  (structural); `DeviceKeyRepositoryAdapter` upserts a `KyberPreKeyEntity` (new, mirrors
  `SignedPreKeyEntity`) + `SpringDataKyberPreKeyRepository`. Validation: base64 decode, exact lengths,
  no cryptographic signature check (client does it at X3DH).
- **Slice 2 fetch path:** `PreKeyBundleView`, `FetchPreKeyBundleUseCase`/`PreKeyBundleQueryAdapter`, and
  `PreKeyBundleResponse` include the kyber prekey (always present — no atomic claim, unlike the EC OTP).

## iOS (extends Slice 1 + Slice 2 work)

- **`SignalKeyGenerator` (Slice 1):** also generate a `KEMKeyPair` (`KEMKeyPair.generate()`), a kyber key
  id, and the identity-key signature over the serialized kyber public key (same call used for the signed
  prekey signature). Add the kyber prekey to `GeneratedDeviceKeys`.
- **`PublicKeyBundle` (Slice 1)** and **`RemotePreKeyBundle` (Slice 2)** gain the `kyberPreKey` field.
- **`DeviceKeyVault` (Slice 1):** persist the kyber prekey private material during registration.
- **`SignalKeyStore` (Slice 2 already adds `SessionStore`):** additionally conform to **`KyberPreKeyStore`**
  with a `kyber_prekey` table (`storeKyberPreKey`/`loadKyberPreKey`/`markKyberPreKeyUsed`); `markKyberPreKeyUsed`
  is a no-op for the reusable last-resort key.
- **`SessionEstablisher` (Slice 2 C5):** construct the libsignal `PreKeyBundle` with the kyber args
  (`kyberPrekeyId:kyberPrekey:kyberPrekeySignature:`), decoding the `KEMPublicKey` from the fetched bundle.

## Spike-verified 0.96.2 API corrections (fold into the Slice 2 plan, Tasks C5/C6/C8)

These are not new design but must be captured so the plan's call sites compile:

- `processPreKeyBundle(_:for:ourAddress:sessionStore:identityStore:now:context:)` — takes the caller's own
  `ProtocolAddress` (`ourAddress`).
- `signalEncrypt(message:for:localAddress:sessionStore:identityStore:now:context:)` — takes `localAddress`.
- `signalDecrypt(message:from:to:sessionStore:identityStore:context:)` — takes the recipient `toAddress`.
- `signalDecryptPreKey(message:from:localAddress:sessionStore:identityStore:preKeyStore:signedPreKeyStore:kyberPreKeyStore:context:)`
  — **requires `kyberPreKeyStore`** (so `SignalKeyStore` must conform to `KyberPreKeyStore`, per above).
- `CiphertextMessage.MessageType` is a **struct**, not an enum — compare with the explicit
  `CiphertextMessage.MessageType.preKey` / `.whisper` (raw values 3 / 2).
- `NullContext`, `InMemorySignalProtocolStore` (conforms to `KyberPreKeyStore`), and `KEMKeyPair` are all
  available for tests.

## Testing

**Server (Testcontainers — Postgres + Keycloak):**
- `PUT /v1/keys` with a `kyberPreKey` persists a `kyber_prekey` row; re-`PUT` replaces it (no duplicate).
- `GET /v1/keys/{sub}` returns the `kyberPreKey`.
- Malformed kyber prekey (bad base64 / wrong length) → 400. Flyway `V3` applies cleanly.

**iOS (Swift Testing, real libsignal, mocks for network):**
- Keygen produces a kyber prekey whose signature **verifies** against the identity key.
- `SignalKeyStore` kyber round-trips through the temp SQLCipher DB.
- **The real proof:** a full PQXDH `establishOutbound → encrypt → decrypt` round-trip between two in-memory
  identities succeeds (the round-trip Task 0 showed is impossible without Kyber). This subsumes the Slice 2
  C5/C6 tests once the kyber args are present.

## Privacy gate (principle 6)

- Only public Kyber key material + its identity signature leave the device; the private Kyber key lives in
  the SQLCipher-encrypted vault. No new cleartext routing/metadata. Server stays content-blind (structural
  validation only; signature verified client-side).

## Plan refresh (next step, not part of this spec's approval)

Refresh `docs/superpowers/plans/2026-06-27-slice-2-x3dh.md`:
- Mark **Task 0 DONE** (Kyber mandatory; record the verified facts).
- Insert **two Kyber tasks before the X3DH tasks**: (1) server — Slice-1 bundle + `V3` + fetch carry the
  kyber prekey; (2) iOS — `SignalKeyGenerator` + `PublicKeyBundle`/`RemotePreKeyBundle` + `DeviceKeyVault` +
  `SignalKeyStore` `KyberPreKeyStore` conformance.
- Apply the API corrections above to Tasks C5/C6/C8.
- Note the `V3` migration in Task A1/contracts and remove the Slice 2 spec's "no migration" assumption for
  key storage (it still holds for the message envelope).
- Un-block A1–D2.

## Tracked / deferred

- **One-time Kyber prekeys + last-resort/signed-prekey rotation + replenishment** — Slice 9 (additive to
  this contract; the server will prefer a one-time Kyber prekey and fall back to this last-resort one).
- **PQ-protecting the ongoing Double Ratchet** (beyond the PQXDH handshake) — future, out of MVP scope.
