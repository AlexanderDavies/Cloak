# Cloak — iOS App

The iOS client for Cloak. End-to-end encrypted messaging with on-device AI inference (Gemma 3N). No message content leaves the device unencrypted.

## Prerequisites

- Xcode 16+ (verified on 26.5) with an installed iOS 17+ simulator runtime
- `brew install xcodegen swiftlint`
- Backend infra for manual end-to-end (`../dev.sh up` — see `../server/README.md`)

## Run locally

```bash
cd app
xcodegen generate          # produces Cloak.xcodeproj (git-ignored)
open Cloak.xcodeproj        # then Cmd+R on an iOS 17+ simulator
```

The app signs in via Keycloak (OIDC-PKCE) and sends/receives an opaque ciphertext blob over the
authenticated WebSocket. (Real E2EE via libsignal and on-device AI arrive in later slices.)

**Sign in** with a seeded local user — `alice` or `bob`, password `password` (see `../iam/README.md`).
Use the iOS **Simulator** so `localhost` reaches your local server.

**Two-simulator round-trip:** run the app on two simulators, sign in as `alice` on one and `bob` on the
other. Each screen shows **You: \<your sub\>**, and the **Recipient sub** field auto-defaults to the
*other* seeded user — so Alice→Bob and Bob→Alice both work out of the box. (You can also paste any other
signed-in user's `sub` into the field manually.)

Press **Return** (with the Simulator's hardware keyboard connected) or tap **Send** to send a message.

The server routes by `sub`, so a message only arrives if its recipient `sub` matches a signed-in user —
**including yourself**: setting the recipient to your own `sub` delivers the message back to you (shows as
an incoming bubble on your own device). Picking recipients by contact/handle instead of raw `sub` is a
later slice.

> If `xcodebuild` can't find Xcode (CLT is the default toolchain), prefix commands with
> `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` or run `sudo xcode-select -s` once.

## Test

```bash
cd app
xcodegen generate
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
