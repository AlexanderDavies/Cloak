# Slice 1 — Account onboarding + device key registration — Design

- **Date:** 2026-06-20
- **Status:** Approved (design); pending implementation planning
- **Branch:** `feature/slice-1-onboarding-keys`
- **Roadmap:** Slice 1 of `2026-06-08-cloak-mvp-roadmap-design.md`
- **Design system:** `2026-06-10-cloak-design-system-design.md` (OneUI tokens)
- **iOS architecture:** `app/docs/ARCHITECTURE_GUIDE.md` (§3, §5, §6, §7)

## Goal

A user self-registers / logs in via Keycloak, the iOS app uses **libsignal** to generate its device
identity key + signed prekey + one-time prekeys, and publishes the **public** bundle to the server, which
stores it in a device/prekey registry. Private keys are persisted **encrypted on-device**. This slice does
**not** establish sessions or send real encrypted messages — that is Slice 2.

## Locked decisions

| Decision | Choice |
|----------|--------|
| **libsignal integration (iOS)** | **CocoaPods** (`LibSignalClient`) — Signal's officially-supported distribution; lowest risk for a crypto dependency. (Phase 0 deferred this; SwiftPM is unsupported.) |
| **Encrypted persistence (iOS)** | **GRDB + SQLCipher**, added this slice via CocoaPods (`GRDB.swift/SQLCipher`) — the first use of the guide's encrypted-at-rest layer. AppAuth stays on SwiftPM (they coexist). |
| **libsignal store scope** | **Approach 1** — implement `IdentityKeyStore` + `PreKeyStore` + `SignedPreKeyStore` on GRDB+SQLCipher now (their data is what this slice generates and must persist). Defer `SessionStore`/`SenderKeyStore` to Slice 2. |
| **Onboarding UI** | Retire the Phase 0 demo send screen; adopt the real shape: login → "Setting up secure keys" → empty conversation list. |
| **Keycloak UI** | **Custom branded login theme** matching OneUI (not stock Keycloak). Self-registration enabled. |
| **Registry write** | `PUT /v1/keys` (idempotent upsert of the calling device's bundle). |
| **One-time prekeys** | 100 (Signal default; a single tunable constant). |
| **Server signature check** | Structural + auth validation only; the signed-prekey signature is verified **client-side** at X3DH (Slice 2), where the trust decision lives. |

## Component map

- **iam** — a custom `cloak` login theme + self-registration.
- **iOS** — `LibSignalClient` + GRDB/SQLCipher via CocoaPods; a `Foundation/Encryption` crypto layer
  (keygen + libsignal stores); a REST publisher; a "Setting up secure keys" screen → empty conversation
  list; retire the Phase 0 demo.
- **server** — a bearer-authenticated REST endpoint to receive, validate, and persist a device's public
  bundle (new usecase + output adapter + repository, hexagonal).
- **db** — Flyway `V2`: extend `device`, add `signed_prekey` + `one_time_prekey`.

## End-to-end flow

1. Login/register via Keycloak (existing OIDC-PKCE, now on the branded theme) → JWT.
2. App checks local registration state. If unset → generate keys, persist privates, build the public
   bundle, `PUT /v1/keys` with the bearer token.
3. Server validates JWT `sub` + bundle structure, upserts the device + replaces its prekeys.
4. App marks the device registered → empty conversation list. Relaunch skips keygen/publish.

## The contract

New artifact `docs/contracts/slice1-device-key-bundle.md` + `slice1-key-bundle.json` (server is authority;
the JSON fixture is copied into the iOS test bundle so the mock can't drift).

**Request — `PUT /v1/keys`** (bearer, `aud: cloak-api`):

```json
{
  "registrationId": 12345,
  "deviceId": 1,
  "identityKey": "base64",
  "signedPreKey": { "keyId": 1, "publicKey": "base64", "signature": "base64" },
  "oneTimePreKeys": [ { "keyId": 1, "publicKey": "base64" } ]
}
```

- **`owner_sub` comes from the validated JWT**, never the body (same anti-spoofing rule as the message
  envelope). `deviceId` = 1 (primary device; multi-device is Slice 5).
- **Idempotent:** re-`PUT` replaces this device's bundle (identity + signed prekey + one-time prekeys).
- **Why PUT (not POST):** the resource is fully addressable up front (the calling device's bundle), and
  registration is retry-prone — PUT is idempotent, so a retried request can't mint a duplicate device.
  *(Replenishment in Slice 9 — appending one-time prekeys to the collection — will be a separate POST.)*
- **Response:** `204 No Content`. (A remaining-one-time-prekey count is added later, in Slice 9, when the
  client needs it to decide when to replenish.)

**Privacy (principle 6):** the entire bundle is public key material by design; private keys are never
uploaded. `owner_sub` is routing identity. All cleartext justified.

## Server (hexagonal — mirrors Phase 0 layout)

`adapter/input/rest/keys` (`DeviceKeyController` + request DTO) → `usecase/RegisterDeviceKeysUseCase` →
`port/output/DeviceKeyRepository` → `adapter/output/database` (JDBC). Reuses the Phase 0 resource-server
bearer auth for REST.

**Validation = structural + auth only:** valid JWT (`aud: cloak-api`, `sub` present); bundle shape, base64
decodes, sane key sizes, ≤ 100 one-time prekeys, unique key ids. No cryptographic signed-prekey
verification server-side (the receiving client does that during X3DH; the server stays content-blind).

## DB — Flyway `V2`

```sql
ALTER TABLE device
  ADD COLUMN registration_id INTEGER,
  ADD COLUMN device_number   INTEGER NOT NULL DEFAULT 1,        -- libsignal deviceId; multi-device = Slice 5
  ADD CONSTRAINT uq_device_owner_number UNIQUE (owner_sub, device_number);
-- device.public_key (V1) holds the identity public key; algorithm notes the curve.

CREATE TABLE signed_prekey (
  device_id   UUID        NOT NULL REFERENCES device(id) ON DELETE CASCADE,
  key_id      INTEGER     NOT NULL,
  public_key  BYTEA       NOT NULL,
  signature   BYTEA       NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (device_id, key_id)
);

CREATE TABLE one_time_prekey (
  device_id   UUID        NOT NULL REFERENCES device(id) ON DELETE CASCADE,
  key_id      INTEGER     NOT NULL,
  public_key  BYTEA       NOT NULL,
  consumed_at TIMESTAMPTZ,                                       -- NULL = available; Slice 2 consumes one per X3DH
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (device_id, key_id)
);
CREATE INDEX idx_otp_available ON one_time_prekey (device_id) WHERE consumed_at IS NULL;
```

Re-`PUT` upserts the device by `(owner_sub, device_number)` and replaces its signed prekey + one-time
prekeys.

## iOS

**Build system.** Add a `Podfile` (`pod 'LibSignalClient'`, `pod 'GRDB.swift/SQLCipher'`). Flow becomes
`xcodegen generate && pod install` → open `Cloak.xcworkspace`. Update `scripts/coverage.sh` (build the
workspace), `README.md`, and `.gitignore` (ignore `Pods/`, commit `Podfile.lock`).

**Crypto — `Foundation/Encryption` (guide §7).** `SignalKeyGenerator` produces the `IdentityKeyPair`, a
`registrationId`, one signed prekey (+ identity-signed signature), and 100 one-time prekeys. Private
material is persisted through libsignal's store protocols — `IdentityKeyStore`, `PreKeyStore`,
`SignedPreKeyStore` — implemented on **GRDB + SQLCipher**. The SQLCipher passphrase is generated once and
held in the **Keychain**; the DB file is the encrypted-at-rest vault for all private keys.

**Publish + orchestration.** A `KeyBundle` value type carries the public bundle; a `DeviceKeyPublisher`
(Foundation/Network, protocol + mock) `PUT`s it to `/v1/keys` with the bearer token. A
`DeviceRegistrationService` is **idempotent/resumable**: keys exist but publish failed → re-publish without
regenerating; on success → mark the device registered (flag in the encrypted store).

**UX (guide §3, OneUI).** After login, unregistered devices route to a **"Setting up secure keys"** screen
whose `@Observable` view model drives `generating → publishing → done / failed(retry)` (reusing the
surfaced-error discipline). On done → an **empty conversation list** (the new home). The Phase 0 demo
conversation screen + `DemoUser`/recipient hack are retired; the `MessageTransport`/envelope seam stays as
dormant infra Slice 2 builds the real chat on (with its existing unit tests kept green).

## iam — custom `cloak` login theme

- Theme at `iam/themes/cloak/login/` extending Keycloak's base, mounted into the Keycloak container
  (dev: volume + theme cache disabled so edits show live); realm sets `loginTheme: "cloak"`.
- **Pages:** login + registration via an overridden page shell (`template.ftl`) + CSS, so
  password-reset/verify/error inherit the same chrome.
- **OneUI match:** `primary` `#6D28D9` buttons/links (pressed `#5B21B6`), `success` `#22C55E` accents,
  `surface` inputs with `separator` borders at `radius-sm` (10), the Cloak logo, system font stack
  (`-apple-system`/SF), 8-pt spacing, **light + dark** via `prefers-color-scheme` (dark is the hero).
- Self-registration enabled (`registrationAllowed=true`).

## Testing

Contract-first, then TDD both sides. Shared fixture keeps the iOS mock honest.

**Server (Testcontainers — Postgres + Keycloak, integration-heavy):**
- Authenticated `PUT /v1/keys` → device + signed_prekey + one_time_prekey rows persisted; `owner_sub` from
  the JWT.
- Idempotent re-`PUT` replaces the bundle (no duplicate device); malformed bundle → 400;
  unauthenticated / wrong-audience → 401/403; Flyway `V2` applies cleanly. Domain unit tests for
  validation. JaCoCo ≥ 90%.

**iOS (Swift Testing, mocks, no backend):**
- Keygen: valid identity pair; the signed-prekey signature **verifies** against the identity key; exactly
  100 unique one-time prekeys (real libsignal — pure on-device).
- Store round-trips through the GRDB+SQLCipher libsignal stores (temp DB).
- Bundle builder matches the contract fixture; `DeviceKeyPublisher` serializes + `PUT`s via a mock.
- `DeviceRegistrationService`: first run generates+publishes+marks; relaunch skips; **resume** after a
  failed publish re-publishes without regenerating. Setup view-model states. Xcode coverage ≥ 90%
  (views/app-entry/CocoaPods/platform-edge adapters excluded).

**iam/theme:** manual E2E (themed login/register in light + dark) + an assertion that the realm export sets
`loginTheme: cloak` and self-registration on, and the theme files are present/valid.

## Acceptance (slice Definition of Done)

- Two users self-register / log in on two simulators → two device rows + queryable public bundles (verified
  via integration test + DB).
- Branded login/register renders in Cloak's look (light + dark).
- Relaunch does not regenerate or re-publish.
- Repo quality gates pass both sides (≥ 90% coverage, lint, skill **and** manual review); READMEs updated;
  the full stack comes up via `./dev.sh up` + `./gradlew bootRun` + the Xcode **workspace**.

## Privacy gate (principle 6, verified for this slice's data)

- **Only public key material leaves the device** — assert the upload body carries no private keys; privates
  are never serialized to the network.
- Private keys at rest live in the **SQLCipher-encrypted** DB; the passphrase in the **Keychain**.
- **No keys/tokens/secrets logged** (reuse the no-plaintext discipline).
- Postgres stores only **public** keys + routing identity (`owner_sub`); no plaintext content anywhere.
- Keycloak owns credentials — the app never sees passwords (TLS in prod; local-dev http exception only).

## Tracked / deferred

- **`deviceId` type reconciliation** — Phase 0's message envelope uses a UUID `deviceId`; libsignal uses an
  integer device id (primary = 1). Reconcile the protocol address (`sub` + integer device id) with the
  envelope in Slice 2 / the multi-device slice.
- **Client-side signed-prekey verification** — enforced by the receiver during X3DH (Slice 2).
- **Prekey replenishment + signed-prekey rotation + revocation** — Slice 9 (incl. the append POST endpoint).
- **Sessions** (`SessionStore`, X3DH, Double Ratchet) — Slices 2–3.
- **Contact discovery** (human handle → `sub`) — unsolved gap from the Phase 0 contract; out of MVP scope.
