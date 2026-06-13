# iOS Architecture Guide — Design Spec

> Brainstormed design for the Cloak **iOS client architecture guide**. The deliverable is a new
> `app/docs/ARCHITECTURE_GUIDE.md` (with `app/CLAUDE.md` reduced to a short pointer), mirroring the
> server's `CLAUDE.md` + `docs/ARCHITECTURE_GUIDE.md` split. This spec records the **decisions**; a
> follow-on implementation plan (via `writing-plans`) authors the guide section by section.

## Goal

Produce an **opinionated, best-in-class, mechanically-enforced** architecture guide for the SwiftUI
iOS client — at the depth of `server/docs/ARCHITECTURE_GUIDE.md` — covering the **full target
architecture** so every roadmap slice is built consistently from day one. iOS is not the maintainer's
specialty; the guide must therefore make strong, justified choices rather than present open options.

## Context (current state)

- **`app/CLAUDE.md`** — a 152-line sketch of a 5-layer architecture (App → UI Feature → Page → Data →
  Foundation, with Router/Page/View/ViewModel/Presenter/DataTransformer/Repository and Foundation
  modules incl. OneUI + Encryption). To be **modernized, folded in, and superseded** by the new guide.
- **Tooling already decided** (`docs/superpowers/plans/2026-06-10-phase-0-ios-skeleton.md`): XcodeGen,
  AppAuth (OIDC-PKCE), libsignal, SwiftLint, an `xccov` ≥90% coverage gate, and **mock-based testing**
  (the app isolates from the backend — no Testcontainers, no live server).
- **Design system** — OneUI (Royal & Spring), specced `2026-06-10-cloak-design-system-design.md`.
- **Bar / format** — the server guide is the depth and prescriptiveness target.
- **Non-negotiable Cloak invariants** (root `CLAUDE.md`): E2EE (server untrusted with content);
  on-device AI only; privacy by design (most-restrictive default); minimal cleartext metadata; use
  well-audited crypto, never roll custom.

## Decisions (the forks, settled during brainstorming)

1. **Stance — modernize the shape.** Keep the layered spirit and `Cloak<Layer><Feature>` naming, but
   bring it to current iOS-17+ best practice: `@Observable` (Observation) over ObservableObject/Combine,
   **drop the legacy Presenter**, right-size the Page indirection (View + ViewModel + display mapping),
   adopt Swift Testing, and add server-guide-level depth + code examples + "refuse this" anti-patterns.
2. **Breadth — full target architecture.** Define the boundary, abstraction, and rules for areas that
   land in later slices (Signal session/key management, encrypted-at-rest persistence,
   reconnection/offline send queue, on-device AI inference, multi-device) **now**, even where
   implementation comes later — the way the server guide pre-defines patterns.
3. **Enforcement — mechanically enforced.** The architecture is guaranteed by tooling, not just
   documented:
   - **SwiftPM local-package module boundaries** so a layer violation is a **compile error** (the iOS
     analogue to the server's ArchUnit — a Foundation package literally cannot import a Feature).
   - SwiftLint **strict** + custom rules + **file-suffix conventions**.
   - A build-failing **≥90% coverage gate** (views / app-entry / generated excluded).
   - CI runs the lint + test + coverage gates.
   - **Pragmatic path:** start with a few packages and split as features grow; the package graph
     enforces direction throughout.
4. **Local persistence — GRDB + SQLCipher.** Whole-database AES-256 encryption (SQLCipher) via GRDB;
   DB key in the Keychain (Secure-Enclave-gated where possible). **Rationale:** the Signal Double
   Ratchet deletes message keys on decrypt (forward secrecy), so history **must** be persisted as
   plaintext locally once decrypted → encryption-at-rest is mandatory, not optional. SQLCipher is
   well-audited (honors "no custom crypto"), is exactly what Signal's own iOS client uses, gives real
   queries (threads, FTS5 search, pagination) inside the encrypted store, and is highly testable
   (in-memory encrypted DBs). SwiftData was rejected (no native encryption-at-rest, couples
   persistence to SwiftUI, still maturing); plain encrypted-blob files were rejected (no structured
   queries).

## Opinionated stack (the rest, brought as recommendations and confirmed)

- **Presentation:** SwiftUI + `@MainActor @Observable` view models; dumb views; display-model mapping
  kept out of the view; `NavigationStack` + enum-route `Router` per feature; no UIKit coordinators.
- **Concurrency:** Swift 6 language mode, **strict (complete) concurrency**; `Sendable` throughout;
  actors for stateful infrastructure (transport, crypto, AI, storage); `@MainActor` for UI.
- **Dependency injection:** manual **constructor injection** + a single **composition root** in the App
  layer; no singletons/globals, no DI framework; protocol-per-boundary so tests inject fakes.
- **Transport:** a `MessageTransport` protocol (the mock seam); an actor owns the WebSocket primary +
  long-poll fallback, reconnection/backoff, and a **persisted offline outbox** replayed on reconnect;
  explicit ordering, dedupe/idempotency, acks/receipts.
- **Crypto / Signal:** `CloakFoundationEncryption` wraps libsignal — a `SignalProtocolStore` over the
  encrypted store; identity / prekeys / sessions as domain types; private keys in Keychain (Secure
  Enclave where possible); **plaintext never crosses the encryption boundary outward**; no plaintext
  logging.
- **On-device AI:** `CloakFoundationInference` exposes a narrow `LocalAssistant` protocol (prompt →
  streamed tokens) over a **pluggable runtime** (Core ML / MLX / llama-style); the boundary is fixed
  now, the runtime choice is deferred to its slice; message content never leaves the device.
- **Auth:** `AuthService` protocol + AppAuth OIDC-PKCE against Keycloak; tokens in the Keychain;
  refresh/expiry handling; token attached to the transport.
- **Testing:** **Swift Testing** for logic/data/view-models against mocks (fast, hermetic, no backend);
  XCUITest for navigation flows; snapshot tests for OneUI; **shared contract fixtures** keep the mock
  `MessageTransport` honest; ≥90% coverage gate.
- **Client observability:** OSLog/`Logger` with privacy redaction (`%{private}`) + signposts + crash
  diagnostics; never content/PII (mirrors server §10 intent on the client).
- **Error handling:** typed errors per boundary; clear user-facing surfacing; graceful transport
  degradation; the message-status lifecycle (Sending → Sent → Delivered → Read → Failed); no silent
  drops.
- **Security hardening:** Keychain access controls / Secure Enclave; TLS pinning for the WebSocket; no
  plaintext logging; sensitive stores excluded from backups.

## Guide structure (18 sections of `app/docs/ARCHITECTURE_GUIDE.md`)

**Foundations**
- **§0 Operating principles for Claude** — ask/validate/README/DRY-KISS-SOLID/runnable-locally + the
  Cloak invariants mapped to iOS. *(Mirrors server §0.)*
- **§1 Architectural principles** — the layered model, unidirectional dependency rule,
  protocol-defined seams, TDD-as-inner-loop, privacy/E2EE/on-device-AI invariants.
- **§2 Project layout & modularization** — the SwiftPM package graph (App → Feature → Data →
  Foundation), `Cloak<Layer><Feature>` naming, file-suffix conventions, the composition root, and how
  the package graph **compile-enforces** the layering.

**The app's shape**
- **§3 Presentation** — dumb SwiftUI views, `@MainActor @Observable` view models, display-model
  mapping, `NavigationStack` + enum-route Router, anti-patterns.
- **§4 Concurrency** — Swift 6 strict/complete checking, actors for stateful infra, `@MainActor` for
  UI, structured concurrency, pitfalls.
- **§5 Dependency injection & composition** — constructor injection only, protocol-per-boundary, the
  composition root, test seams.

**Data & domain boundaries**
- **§6 Data layer** — repository protocols; GRDB + SQLCipher encrypted store; schema/migrations, FTS5
  search, pagination; in-memory + disk caching; encryption-at-rest key handling.
- **§7 Cryptography & the Signal boundary** — `CloakFoundationEncryption` wrapping libsignal;
  `SignalProtocolStore` over the encrypted store; identity/prekeys/sessions; the "decrypt once →
  persist plaintext" rule; plaintext-never-crosses-outward invariant.
- **§8 Transport & delivery** — `MessageTransport` mock seam; WebSocket actor (auth,
  reconnection/backoff); long-poll fallback; persisted offline outbox, ordering, dedupe, acks/receipts;
  contract fixtures.
- **§9 On-device AI boundary** — `CloakFoundationInference`, `LocalAssistant` protocol (streaming),
  pluggable runtime (deferred), memory budget, content-never-leaves-device.
- **§10 Auth** — `AuthService` + AppAuth OIDC-PKCE against Keycloak; token storage/refresh.

**Cross-cutting**
- **§11 Error handling & resilience** — typed per-boundary errors; the message-status lifecycle; retry
  vs fail-fast; no silent drops.
- **§12 Client observability** — OSLog with privacy redaction, signposts, crash diagnostics; never
  content/PII.
- **§13 Design system (OneUI)** — package, Royal & Spring tokens, snapshot tests; links the
  design-system spec.

**Discipline & enforcement**
- **§14 TDD & testing strategy** — Swift Testing inner loop, mock-based/hermetic, the test-type matrix
  (logic/contract/UI/snapshot), contract fixtures, ≥90% coverage gate, in-memory encrypted DB.
- **§15 Tooling, build & quality gates** — XcodeGen + SwiftPM, SwiftLint (strict + custom rules),
  coverage gate, CI.
- **§16 Security hardening** — Keychain/Secure Enclave, TLS pinning, no plaintext logging, backup
  exclusion.
- **§17 Anti-patterns to refuse** (consolidated) · **§18 New-feature bootstrap checklist.**

## Deliverables

- **`app/docs/ARCHITECTURE_GUIDE.md`** — the new guide (the 18 sections above, with code examples and
  anti-patterns, at server-guide depth).
- **`app/CLAUDE.md`** — reduced to a short pointer + the non-negotiable invariants + a "read the guide
  before feature work" instruction, exactly like `server/CLAUDE.md`.
- **`app/README.md`** — updated if any run/build/test guidance changes.

## Non-goals

- Not writing feature implementation plans (the guide is reference architecture, not a feature).
- Not selecting the on-device AI runtime — the **boundary** is fixed; the runtime is chosen at its slice.
- Not rewriting the Phase 0 iOS plan. It currently uses XCTest; adopting Swift Testing implies a small
  refresh to that plan, which is **flagged here as a follow-up**, not done as part of this work.

## Deferred / elaborated-at-their-slice

- Multi-device key management and the offline-queue's finer semantics get their boundary + rules in the
  guide now; full detail is elaborated when their slice arrives.
- The AI runtime (Core ML / MLX / llama-style) selection.
