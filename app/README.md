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

## Onboarding flow (Slice 1)

After signing in via Keycloak (OIDC-PKCE), the app runs the Slice 1 onboarding flow:

1. **Login** — a Cloak-branded Keycloak login page (OIDC-PKCE). Self-registration is enabled; new users
   can create an account directly from the login screen.
2. **"Setting up secure keys…"** — the app generates a libsignal device-key bundle entirely on-device
   (identity key pair, signed prekey, 100 one-time prekeys). The public bundle is published to the server
   via `PUT /v1/keys`. **Private keys never leave the device**; they are stored in the
   SQLCipher-encrypted on-device database (GRDB + SQLCipher, passphrase held in the iOS Keychain).
3. **Empty conversation list** — the destination screen. Messaging is added in Slice 2.

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
