# Cloak — iOS App

The iOS client for Cloak. End-to-end encrypted messaging with on-device AI inference (Gemma 3N). No message content leaves the device unencrypted.

## Prerequisites

- Xcode 16+ (verified on 26.5) with an installed iOS 17+ simulator runtime
- `brew install xcodegen swiftlint cocoapods`
- Backend infra for manual end-to-end (`../dev.sh up` — see `../server/README.md`)

## Run locally

```bash
cd app
xcodegen generate          # regenerates Cloak.xcodeproj (git-ignored)
pod install                # fetches LibSignalClient + GRDB/SQLCipher; creates Cloak.xcworkspace
open Cloak.xcworkspace     # always open the workspace, never the bare .xcodeproj
# Cmd+R on an iOS 17+ simulator
```

> `Pods/` and `Cloak.xcworkspace` are git-ignored. Run `xcodegen generate && pod install` after
> cloning or rebasing to recreate them. `Podfile` and `Podfile.lock` are tracked.

> **Bumping libsignal:** update BOTH the `tag:` in `Podfile` AND the
> `LIBSIGNAL_FFI_PREBUILD_CHECKSUM` to the SHA-256 from the matching GitHub release asset
> (`libsignal-client-ios-build-<tag>.tar.gz.sha256`). A mismatch is a hard build error.

## Onboarding + messaging flow (Slices 1–3)

After signing in via Keycloak (OIDC-PKCE), the app runs the onboarding flow and opens the
conversation list:

1. **Login** — a Cloak-branded Keycloak login page (OIDC-PKCE). Self-registration is enabled; new users
   can create an account directly from the login screen.
2. **"Setting up secure keys…"** — the app generates a libsignal device-key bundle entirely on-device
   (identity key pair, signed prekey, 100 one-time prekeys). The public bundle is published to the server
   via `PUT /v1/keys`. **Private keys never leave the device**; they are stored in the
   SQLCipher-encrypted on-device database (GRDB + SQLCipher, passphrase held in the iOS Keychain).
3. **Conversation list** — renders from local encrypted history (last message + timestamp per
   conversation), so it appears **before any network call**, then triggers a backlog sync to catch up
   on anything missed while the app was closed. Tapping a row opens that thread. A first conversation
   is started by resolving a peer handle (email or username) to a `ResolvedRecipient` via
   `GET /v1/users/lookup`.
4. **Chat thread** — restores its history from local storage on open, then sends/receives live.
   On the first send to a new peer, the app fetches their pre-key bundle (`GET /v1/keys/{sub}`) and
   runs PQXDH (X3DH + ML-KEM-1024) to establish a session. The first message is a
   `PreKeySignalMessage`; every subsequent message steps the Double Ratchet (forward secrecy).
   Each message's plaintext is `{ sentAt, text }`, encrypted as the Signal payload — the send
   timestamp never travels as cleartext metadata. The thread sorts and inserts by that
   sender-authoritative `sentAt`, so a late-arriving message lands at its correct position rather
   than being appended. A sent message moves `sending → sent` (or `→ failed` on a transport error).
   Messages are grouped under centred day separators ("Today", "Yesterday", weekday, then date). Each
   bubble shows its time inside, on its own line beneath the text, right-aligned; the Sending/Sent mark + label joins it
   only on the latest outgoing message, and a Failed mark + label on every failed message.
   A send interrupted by the app being killed shows as Failed on the next launch. All libsignal
   operations share one lock, so backlog sync, live receive and sending never race on a session.

Local encrypted history (GRDB `message` table in the same SQLCipher database as the keys) is
**authoritative** for what a device has seen: Double-Ratchet decryption is destructive, so a message
can only ever be decrypted once and the server cannot re-serve it afterwards. On launch/foreground,
`BacklogSync` pulls `GET /v1/messages` from the device's stored high-water mark, decrypts each
not-yet-seen message exactly once, and persists it; delivery via the live `/ws` frame and the
backlog pull both dedupe on `messageId`, so the same message is never rendered twice regardless of
which path it arrives on. That dedupe happens **before** the decrypt: the server's queue is
stateless and re-serves messages already delivered live, and re-decrypting one is impossible, so a
message already in local history is skipped and the high-water mark simply moves past it. A message
that can never be opened (invalid base64, a failed decrypt, or malformed plaintext — e.g. sealed to a
since-replaced identity) is skipped and counted, and the conversation list says how many, so it cannot
wedge the messages behind it. A transient local failure (the dedupe check or the save) does **not**
advance the mark: it is retried on every later sync and the list shows a banner until it succeeds.

Key generation uses [LibSignalClient](https://github.com/signalapp/libsignal) (CocoaPods, prebuilt FFI
binary). The on-device database is encrypted via GRDB/SQLCipher; the passphrase is a 32-byte random
secret stored in the iOS Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`).

Registration is idempotent: if the app is force-quit during setup, the next launch retries without
regenerating keys.

**Sign in** with a seeded local user — `alice` or `bob`, password `password` (see `../iam/README.md`) —
or self-register a new account via the branded login page. Use the iOS **Simulator** so `localhost`
reaches your local server.

> If `xcodebuild` can't find Xcode (CLT is the default toolchain), prefix commands with
> `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` or run `sudo xcode-select -s` once.

## Test

```bash
cd app
xcodegen generate && pod install
SIM="iPhone 17" ./scripts/coverage.sh   # Swift Testing + coverage; fails below 90%
```

Tests run against a **mocked** `MessageTransport` — no backend required. SwiftLint runs as a build phase
and fails on violations. The coverage gate counts the app's own sources, excluding pure views, the app
entry, and platform-edge adapters (the real WebSocket/AppAuth, verified by manual E2E).

## Architecture

Layered SwiftPM modules — **App → Feature → Data → Foundation** — with the dependency direction
**compile-enforced** by the package graph, SwiftUI + `@Observable`, GRDB + SQLCipher encrypted-at-rest
storage, and a mock-based (no-backend) test suite. The full, opinionated guide is
**[`docs/ARCHITECTURE_GUIDE.md`](docs/ARCHITECTURE_GUIDE.md)**; `CLAUDE.md` is the short pointer into it.
