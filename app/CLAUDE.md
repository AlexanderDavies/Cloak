# CLAUDE.md — Cloak iOS App

Swift 6 / SwiftUI iOS client (iOS 17+): end-to-end encrypted messaging over WebSocket (with long-poll
fallback), with **all AI assistance running on-device**. Encryption/decryption happen here — the server is
**untrusted with message content** and holds ciphertext only (see root `Cloak/CLAUDE.md` for the
E2EE / on-device-AI / privacy invariants).

## Architecture guide — read before feature work

The full engineering guide (modernized layered modules under mandatory TDD, mechanically enforced by the
SwiftPM package graph) lives in **[`docs/ARCHITECTURE_GUIDE.md`](docs/ARCHITECTURE_GUIDE.md)**.

**Before scaffolding a package, building a feature, or changing any boundary (repository, transport, crypto,
auth, view model), read `docs/ARCHITECTURE_GUIDE.md` end-to-end.** It is the source of truth for the layering,
file-suffix conventions, the crypto/transport/AI boundaries, the testing strategy, and the anti-patterns list.
The summary below does not replace it.

## Non-negotiable Cloak invariants (guide §0.6)

These sit above everything else when they conflict:

- **E2EE is the client's job.** Encryption/decryption happen on-device; plaintext never crosses the
  encryption boundary outward (only ciphertext reaches the transport, logs, or anywhere off-device).
- **On-device AI only.** No prompt or response ever leaves the device. There is no hosted-LLM code path, and
  one must never be added.
- **Graceful degradation.** Messaging behaves identically over WebSocket and long-poll.
- **Privacy by design + minimal cleartext metadata.** Default to the most restrictive option; encrypt at rest
  (GRDB + SQLCipher); never log content, keys, tokens, or PII.

## Operating principles — apply on every cycle (guide §0)

Behaviours, not architecture. They govern every task regardless of whether the full guide is open.

1. **Ask, never assume (§0.1).** Ambiguous contract shape, navigation flow, optionality, or which layer owns
   a responsibility → stop and ask via `AskUserQuestion`. Don't guess envelope shapes, token formats, or
   error states.
2. **Validate every assumption (§0.2).** Read the type/protocol, run the test, launch the simulator before
   *and* after. Memory describes the world when written; confirm it still holds.
3. **Every change updates the README (§0.3).** Any change to how the app is built, run, configured, or tested
   updates `README.md` in the same change set.
4. **DRY · KISS · SOLID, Swift-idiomatically (§0.4).** Single source of truth; simplest design; rule of three
   before abstracting; **constructor injection only** (no singletons/globals); value types + composition.
5. **Runnable + testable locally, no backend (§0.5).** The suite mocks its dependencies and runs on any
   Mac/CI with no server, no Docker. If a change breaks that, add the protocol seam + fake alongside it.

## TDD is mandatory (guide §14)

Red → green → refactor on every feature, fix, and behaviour-affecting change. The inner loop is **Swift
Testing against mocks** — fast, hermetic, no backend (the deliberate inverse of the server's
Testcontainers approach). XCUITest covers flows; snapshot tests cover OneUI. ≥90% coverage gate
(views/app-entry/generated excluded).

## Where to find it in the guide

| Need | Section |
|------|---------|
| **Cloak invariants + the privacy boundary mapped onto every section** | **§0.6** |
| Layered modules, the SwiftPM package graph, naming, file-suffix conventions | §1–§2 |
| Presentation — dumb views, `@Observable` view models, navigation | §3 |
| Concurrency — Swift 6 strict, actors, `@MainActor` | §4 |
| Dependency injection + the composition root | §5 |
| Data layer — repositories, GRDB + SQLCipher, encryption-at-rest | §6 |
| Cryptography & the Signal boundary (libsignal, key storage, "decrypt once") | §7 |
| Transport & delivery — WebSocket, long-poll fallback, offline outbox | §8 |
| On-device AI boundary | §9 |
| Auth — OIDC-PKCE via AppAuth | §10 |
| Error handling + the message-status lifecycle | §11 |
| Client observability (OSLog, privacy redaction) | §12 |
| Design system (OneUI) | §13 |
| TDD & testing strategy (Swift Testing, mocks, contract fixtures, coverage) | §14 |
| Tooling, build & quality gates (XcodeGen, SwiftLint, CI) | §15 |
| Security hardening | §16 |
| Anti-patterns to refuse | §17 |
| New-feature bootstrap checklist | §18 |

## Module naming

```
Cloak<Layer><Feature>
e.g. CloakFeatureConversation, CloakDataMessaging, CloakFoundationEncryption, CloakOneUI
```
