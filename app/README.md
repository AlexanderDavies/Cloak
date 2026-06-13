# Cloak — iOS App

The iOS client for Cloak. End-to-end encrypted messaging with on-device AI inference (Gemma 3N). No message content leaves the device unencrypted.

## Prerequisites

- Xcode 16+
- iOS 17+ simulator or physical device
- Server running locally (see `../server/README.md`)

## Run locally

1. Open `Cloak.xcodeproj` (or `Cloak.xcworkspace` if using CocoaPods/SPM workspace) in Xcode
2. Select a simulator or connected device
3. Press **Cmd + R** to build and run

## Architecture

Layered SwiftPM modules — **App → Feature → Data → Foundation** — with the dependency direction
**compile-enforced** by the package graph, SwiftUI + `@Observable`, GRDB + SQLCipher encrypted-at-rest
storage, and a mock-based (no-backend) test suite. The full, opinionated guide is
**[`docs/ARCHITECTURE_GUIDE.md`](docs/ARCHITECTURE_GUIDE.md)**; `CLAUDE.md` is the short pointer into it.
