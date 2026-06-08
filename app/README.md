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

Five layers — App, UI Feature, Page, Data, Foundation — with strict top-down dependencies. See `CLAUDE.md` for the full architecture diagram and rules.
