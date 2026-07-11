# Slice 2 — X3DH session + first real encrypted message — Design

- **Date:** 2026-06-27
- **Status:** Approved (design); pending implementation planning
- **Branch:** `feature/slice-2-x3dh` (to be created)
- **Roadmap:** Slice 2 of `2026-06-08-cloak-mvp-roadmap-design.md`
- **Builds on:** Slice 1 (`2026-06-20-slice-1-onboarding-keys-design.md`) — device + prekey registry, GRDB+SQLCipher libsignal stores
- **iOS architecture:** `app/docs/ARCHITECTURE_GUIDE.md` (§3, §5, §6, §7)

## Goal

Retire the opaque-blob placeholder. A sender looks up a recipient, fetches their public prekey bundle,
runs **X3DH** to establish a Signal session, and sends a **genuinely E2EE** message; the recipient
establishes its side of the session and decrypts. The server sees only ciphertext and minimal routing
metadata. Persisted history, out-of-order tolerance, and multi-message ratchet stepping are **Slice 3**;
this slice proves a single real encrypted round-trip.

## Locked decisions

| Decision | Choice |
|----------|--------|
| **Recipient lookup** | Minimal **exact-match** server endpoint (`GET /v1/users/lookup?handle=…`) resolving an exact username/email → `sub`. No listing, prefix, or autocomplete; rate-limited; no enumeration. The full privacy-preserving contact-discovery design remains a separate future initiative (see Phase 0 envelope contract gap). |
| **Chat thread scope** | **Ephemeral live display** — messages exchanged while connected are held in-memory only; relaunch shows an empty thread. Local encrypted history + restore is **Slice 3**. |
| **Message type signalling** | **Explicit `messageType` field** in the envelope (`2` = normal SignalMessage, `3` = PreKeySignalMessage) so the recipient selects the decrypt path. Cleartext, justified under principle 6 (required-for-decrypt-routing; revisited under sealed-sender hardening, like `fromSub`). |
| **One-time prekey consumption** | **Atomic claim-on-fetch** — a single `UPDATE … FOR UPDATE SKIP LOCKED … RETURNING` statement selects and marks one available one-time prekey consumed in one round-trip. Race-free; Signal-server standard. |
| **No-prekey fallback** | If a device has no available one-time prekey, the bundle is returned **without** one — libsignal X3DH is valid without a one-time prekey (slightly reduced forward secrecy on the first message until Slice 9 replenishment). |
| **deviceId reconciliation** | Retire the Phase 0 **UUID** `deviceId` in the message envelope; address by libsignal's **integer device number** (`fromDeviceId` / `toDeviceId`, always `1` this slice). The device-row UUID stays an internal server primary key. |
| **SessionStore** | Implement the deferred libsignal `SessionStore` on the existing GRDB+SQLCipher vault, alongside Slice 1's `IdentityKeyStore` / `PreKeyStore` / `SignedPreKeyStore`. |
| **Signed-prekey signature verification** | Performed **client-side** by the initiator during `processPreKeyBundle` (where the trust decision lives); the server stays content-blind. |

## Component map

- **server** — a `UserLookupController` (exact handle → `sub` via Keycloak Admin API) and a prekey
  bundle-fetch endpoint that atomically consumes a one-time prekey; the message envelope DTO gains
  `messageType` + integer device-number fields. Routing/Kafka path otherwise unchanged.
- **iOS** — a `SessionStore` on GRDB+SQLCipher; a `SessionEstablisher` (X3DH + signed-prekey verification);
  a `MessageCrypto` (encrypt/decrypt, both message types); `UserLookupClient` + `PreKeyBundleClient`
  (Foundation/Network, protocol + mock); start-conversation + chat-thread screens; the dormant Phase 0
  transport seam now carries the real encrypted blob.
- **db** — **likely no migration** (Slice 1's `one_time_prekey.consumed_at` + partial index already
  support claiming). Confirmed during plan-writing; if a `V3` is needed it is limited and additive.
- **iam** — no change (Keycloak Admin API is already available; the server uses it for exact lookup).

## End-to-end flow

1. A opens **start-conversation**, enters B's exact username/email → `GET /v1/users/lookup?handle=…`
   → `{ sub, deviceId }` (404 if no exact match).
2. A calls `GET /v1/keys/{sub}` → B's bundle (identity key, signed prekey, **one claimed one-time prekey**
   if available). The server atomically consumes the one-time prekey.
3. A's crypto layer **verifies the signed-prekey signature against B's identity key**, runs **X3DH**
   (`processPreKeyBundle`), establishing a session in the `SessionStore`.
4. A encrypts the message → `CiphertextMessage` (type **3 / PreKey**). Sends envelope
   `{ messageId, toSub, toDeviceId, fromDeviceId, messageType, ciphertext }` over `/ws`.
5. Server stamps `fromSub` from the validated JWT, persists ciphertext, publishes to Kafka → delivers to
   B's connected WebSocket session. `ciphertext` stays opaque throughout.
6. B sees `messageType=3` → `signalDecryptPreKey` → establishes its side of the session + decrypts →
   bubble shown (in-memory).
7. B replies → session already established → `CiphertextMessage` type **2 / normal** → A's session
   decrypts it. One genuine encrypted round-trip; the server saw only ciphertext.

## The contracts

Server is authority; JSON fixtures are copied into the iOS test bundle so the mock can't drift.

### New — `docs/contracts/slice2-user-lookup.md`

**`GET /v1/users/lookup?handle=<exact username or email>`** (bearer, `aud: cloak-api`):

```json
// 200
{ "sub": "string (Keycloak sub)", "deviceId": 1 }
// 404 when no exact match
```

- **Exact match only** — no prefix, listing, or autocomplete; rate-limited. Resolved via the Keycloak
  Admin API with `exact=true`.
- Cleartext justification: `handle` is supplied by the caller; the response `sub`/`deviceId` is the minimum
  routing identity required to address a message. No directory/enumeration surface is exposed.

### New — `docs/contracts/slice2-prekey-bundle.md`

**`GET /v1/keys/{sub}`** (bearer, `aud: cloak-api`):

```json
{
  "registrationId": 12345,
  "deviceId": 1,
  "identityKey": "base64 (33 bytes)",
  "signedPreKey": { "keyId": 1, "publicKey": "base64 (33 bytes)", "signature": "base64 (64 bytes)" },
  "oneTimePreKey": { "keyId": 7, "publicKey": "base64 (33 bytes)" }
}
```

- `oneTimePreKey` is **omitted** when the device has none available (valid no-OTP X3DH).
- Fetching **atomically consumes** the returned one-time prekey (marks `consumed_at`). Two concurrent
  fetchers never receive the same one-time prekey (`FOR UPDATE SKIP LOCKED`).
- `404` if `{sub}` has no registered device.
- All fields are public key material or routing identity — no private keys, no plaintext content.

### Updated — `docs/contracts/slice2-message-envelope.md` (supersedes `phase0-message-envelope.md`)

**Inbound frame (client → server, over `/ws`):**

```json
{
  "messageId": "uuid",
  "toSub": "string (recipient Keycloak sub)",
  "toDeviceId": 1,
  "fromDeviceId": 1,
  "messageType": 3,
  "ciphertext": "base64 (serialized libsignal CiphertextMessage)"
}
```

**Delivery frame (server → recipient):** adds server-stamped `fromSub` (from the validated JWT `sub`),
as in Phase 0.

- **Change from Phase 0:** the UUID `deviceId` is replaced by integer libsignal device numbers
  (`toDeviceId` / `fromDeviceId`, both `1` this slice); a `messageType` field is added.
- **Cleartext justification (principle 6):** `messageId` (dedupe/ordering), `toSub`/`toDeviceId`
  (route/target), `fromDeviceId` (sender protocol address for decrypt), `fromSub` (delivery-side,
  server-stamped; receipt routing later), `messageType` (recipient must select PreKey vs normal decrypt
  path), `ciphertext` (opaque). `messageType` and `fromSub` are revisited together under future
  sealed-sender hardening.
- **Sender identity** is still never client-supplied: the server derives `fromSub` from the JWT `sub`.

## Server (hexagonal — mirrors existing layout)

- `adapter/input/rest/users` — `UserLookupController` → `LookupUserUseCase` → a Keycloak Admin API
  port/adapter (exact handle search). Rate-limited; 404 on miss.
- `adapter/input/rest/keys` — `DeviceKeyController.fetchBundle` (`GET /v1/keys/{sub}`) →
  `FetchPreKeyBundleUseCase` → `DeviceKeyRepository.claimOneTimePreKey(deviceId)` running the atomic
  claim statement; assembles the bundle DTO, omitting the one-time prekey when exhausted.
- The WebSocket handler / Kafka producer-consumer path is unchanged except for the envelope DTO and Avro
  record gaining `messageType` and integer device-number fields. `ciphertext` remains opaque bytes.
- Validation: valid JWT (`aud: cloak-api`, `sub` present); `{sub}` resolves to a known device else 404.

### The atomic claim statement

```sql
UPDATE one_time_prekey SET consumed_at = now()
WHERE (device_id, key_id) IN (
  SELECT device_id, key_id FROM one_time_prekey
  WHERE device_id = ? AND consumed_at IS NULL
  ORDER BY key_id LIMIT 1
  FOR UPDATE SKIP LOCKED)
RETURNING key_id, public_key;
```

Returns zero rows when exhausted → the bundle is assembled without a one-time prekey.

## DB — Flyway

**Resolved during plan-writing: no new migration this slice.** Slice 1's `one_time_prekey.consumed_at`
column and `idx_otp_available` partial index already support the atomic claim. The reconciled
`messageType` + integer device-number fields flow through the domain `Message` → Avro envelope for
**live delivery only**; they are not persisted in Slice 2 (the chat thread is ephemeral). Persisting them
— and reconciling the now-vestigial `encrypted_message.device_id` UUID column (left `null` this slice) —
is deferred to **Slice 3 history**, which gets its own migration.

## iOS

- **Foundation/Encryption (guide §7):**
  - `SessionStore` implemented on GRDB + SQLCipher (the deferred libsignal store), alongside Slice 1's
    identity/prekey/signed-prekey stores, sharing the same encrypted vault + Keychain passphrase.
  - `SessionEstablisher` wraps `processPreKeyBundle` — **verifies the signed-prekey signature against the
    fetched identity key** (throws on mismatch) and establishes the initiator session.
  - `MessageCrypto` wraps `SessionCipher` encrypt/decrypt, handling both the PreKey (type 3) and normal
    (type 2) decrypt paths based on the envelope `messageType`.
- **Foundation/Network:** `UserLookupClient` (`GET /v1/users/lookup`) and `PreKeyBundleClient`
  (`GET /v1/keys/{sub}`) — protocol + mock, driven by the shared contract fixtures.
- **Transport:** the dormant Phase 0 `MessageTransport`/envelope seam now carries the real encrypted blob
  and the new envelope fields; existing transport unit tests stay green.
- **UI (guide §3, OneUI, `@Observable`):**
  - **Start-conversation** screen: handle field → lookup → view-model states
    `idle → lookingUp → ready / notFound / failed(retry)`.
  - **Chat thread**: compose + message bubbles; messages held **in-memory only** (no persistence this
    slice). Surfaced-error discipline for lookup-failed, session-establish-failed, decrypt-failed.

## Testing (contract-first, then TDD both sides)

**Server (Testcontainers — Postgres + Keycloak, integration-heavy):**
- Exact-match lookup hit returns `{sub, deviceId}`; miss → 404; auth/audience failures → 401/403.
- Bundle fetch returns identity + signed prekey + one one-time prekey, and **marks exactly one** prekey
  consumed; **concurrent fetches never reuse a one-time prekey** (the key correctness test).
- Exhausted device → bundle returned **without** a one-time prekey.
- New envelope shape (integer device numbers + `messageType`) round-trips through persist + Kafka +
  delivery. Domain unit tests for use cases. JaCoCo ≥ 90%.

**iOS (Swift Testing, mocks, real libsignal — pure on-device):**
- Signed-prekey signature verifies (good case) and **throws on a tampered signature**.
- X3DH `processPreKeyBundle` establishes a session; `SessionStore` round-trips through the temp vault.
- Encrypt → decrypt round-trip for **both** PreKey (type 3) and normal (type 2) messages between two
  in-memory identities.
- `UserLookupClient` / `PreKeyBundleClient` (de)serialize to the contract fixtures via mocks.
- Start-conversation + chat-thread view-model states. Xcode coverage ≥ 90% (views/app-entry/CocoaPods/
  platform-edge adapters excluded).

**E2E acceptance (two simulators):** A looks up B, establishes a session, and exchanges a real encrypted
message both directions; assert no plaintext in Postgres, logs, or Kafka.

## Acceptance (slice Definition of Done)

- Two real users exchange a **genuinely E2EE** message both directions across two simulators; sessions are
  established via X3DH; the server stores/forwards only ciphertext.
- A tampered signed-prekey signature is rejected client-side.
- A one-time prekey is consumed exactly once per fetch; concurrent fetches never collide; exhausted devices
  still yield a usable (no-OTP) bundle.
- Repo quality gates pass both sides (≥ 90% coverage predominantly integration, lint, skill **and** manual
  review); READMEs updated; full stack comes up via `./dev.sh up` + `./gradlew bootRun` + the Xcode
  workspace.

## Privacy gate (principle 6, verified for this slice's data)

- Server stays **content-blind**: only `ciphertext` (opaque), routing identities (`sub`, integer device
  number), `messageId`, and `messageType` are cleartext — each justified above.
- One-time prekeys are consumed exactly once; only public key material is ever served.
- Private keys **and now session state** live in the SQLCipher-encrypted vault; the passphrase in the
  Keychain. Sessions never leave the device.
- Recipient lookup is **exact-match only** — no directory listing, prefix search, or enumeration surface.
- No keys/tokens/plaintext logged; Keycloak still owns credentials (the app never sees passwords).

## Tracked / deferred

- **Double Ratchet across many messages, out-of-order tolerance, persisted + restored history** — Slice 3.
- **Multi-device addressing** (device numbers > 1, per-device fan-out) — Slice 5.
- **Prekey replenishment, signed-prekey rotation, device revocation** — Slice 9.
- **Full privacy-preserving contact discovery** (beyond exact-match lookup) — its own future initiative;
  the Phase 0 envelope-contract gap note still stands.
- **`V3` migration necessity** — resolved during plan-writing: **none this slice** (see DB section).
- **PQXDH / Kyber prekey risk** — LibSignalClient 0.96.2 is PQXDH-era. If its `PreKeyBundle` /
  `processPreKeyBundle` *require* a Kyber prekey, Slice 1's published bundle is insufficient and scope
  grows into Slice 1's keygen + the bundle contract + server storage. The implementation plan opens with a
  verification spike (Task 0) and a decision gate; a mandatory-Kyber outcome is a spec amendment requiring
  approval before implementation.
