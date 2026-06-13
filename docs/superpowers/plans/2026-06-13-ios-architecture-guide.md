# iOS Architecture Guide — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Author `app/docs/ARCHITECTURE_GUIDE.md` — an opinionated, mechanically-enforced, full-target
architecture guide for the Cloak SwiftUI iOS client at the depth of `server/docs/ARCHITECTURE_GUIDE.md` —
and reduce `app/CLAUDE.md` to a short pointer that defers to it (mirroring the server's CLAUDE.md split).

**Architecture:** The guide is **prose + Swift code examples + "refuse this" anti-patterns**, organised
into the 18 sections agreed in the design spec. It is written in section-batches; each batch is one task
that ends with a verification re-read and a commit. Because no Xcode project exists yet (and the repo may
be edited off a Mac), Swift snippets are **illustrative and verified by reading**, not compiled — they
must nonetheless be syntactically valid and use the canonical identifiers fixed in Task 1.

**Tech Stack (the guide documents):** Swift 6 / SwiftUI (iOS 17+), SwiftPM local packages, `@Observable`,
structured concurrency + actors, GRDB + SQLCipher, libsignal, AppAuth (OIDC-PKCE), Swift Testing +
XCUITest + snapshot tests, XcodeGen, SwiftLint, OSLog. Design source of truth:
`docs/superpowers/specs/2026-06-13-ios-architecture-guide-design.md`.

**Workflow:** Branch `feature/planning-ios-architecture` (already created off `main`). This is a
docs-only change — no build to run. Follow root `CLAUDE.md` workflow + quality gates (squash+rebase
before PR, both reviews before merge). Update `app/README.md` only if run/build guidance changes.

> **Authoring guardrails (whole plan):**
> - **Match the bar.** Re-read `server/docs/ARCHITECTURE_GUIDE.md` for tone/format: numbered sections,
>   short prose, code fences, "enforce these" / "refuse them" callouts, tables where they earn their keep.
> - **Be opinionated.** State the choice and *why*, then show the canonical pattern. Avoid "you could".
> - **Privacy first.** Every section that touches user data restates the relevant invariant (E2EE,
>   on-device AI, no plaintext logging, most-restrictive default).
> - **Consistency is mandatory.** Use only the canonical package/type/suffix names from Task 1 §2. A
>   protocol named `MessageTransport` in §8 must not become `MessageService` in §14.

---

## File Structure

- `app/docs/ARCHITECTURE_GUIDE.md` — **new.** The guide (all 18 sections). Built up across Tasks 1–6.
- `app/CLAUDE.md` — **modify (Task 7).** Reduced to: one-paragraph what-this-is + the non-negotiable
  Cloak invariants + a "read `docs/ARCHITECTURE_GUIDE.md` end-to-end before feature work" instruction +
  a section-index table. Mirrors `server/CLAUDE.md`.
- `app/README.md` — **modify (Task 7) only if needed.** Add a one-line link to the guide; otherwise leave.
- `docs/superpowers/plans/2026-06-13-ios-architecture-guide.md` — **this plan** (living-plan: tick boxes
  / record deviations as you go).

**Canonical identifiers (defined in Task 1 §2; referenced verbatim everywhere after):**

- **Packages (SwiftPM local):** `CloakApp` · `CloakFeature<Name>` (e.g. `CloakFeatureConversation`) ·
  `CloakData<Name>` (e.g. `CloakDataMessaging`) · `CloakFoundation<Name>`
  (`CloakFoundationNetwork`, `CloakFoundationEncryption`, `CloakFoundationStorage`,
  `CloakFoundationInference`, `CloakFoundationAuth`, `CloakFoundationDiagnostics`,
  `CloakFoundationUtilities`) · `CloakOneUI` (design system; Foundation-level).
- **Dependency direction:** `CloakApp` → `CloakFeature*` → `CloakData*` → `CloakFoundation*`
  (`CloakOneUI` is importable by Feature + App only). Lower never imports higher — enforced by the
  package graph.
- **File suffixes:** `*View.swift` · `*ViewModel.swift` · `*Route.swift` (+ `*Router.swift`) ·
  `*Repository.swift` · `*Transport.swift` · `*Store.swift` · `*Client.swift` · `*Mapper.swift` ·
  `*DisplayModel.swift` · `*Model.swift`.
- **Key protocols/types:** `MessageTransport` · `MessageRepository` · `LocalAssistant` · `AuthService` ·
  `KeyStorage` · `@MainActor @Observable final class <Screen>ViewModel`.

---

### Task 1: Scaffold + Foundations (§0–§2)

**Files:** Create `app/docs/ARCHITECTURE_GUIDE.md`

- [ ] **Step 1: Create the document skeleton**

Create `app/docs/ARCHITECTURE_GUIDE.md` with the H1 title
`# CLAUDE.md — Swift/SwiftUI iOS Client Architecture Guide` (a one-paragraph preamble: what Cloak's iOS
client is and that this guide is the source of truth), followed by **all 18 section headers** (`## 0.` …
`## 18.`) each with a single italic placeholder line `*Written in Task N.*`. This locks the structure and
lets later tasks fill sections independently.

- [ ] **Step 2: Write §0 Operating principles for Claude**

Mirror `server/docs/ARCHITECTURE_GUIDE.md` §0, iOS-flavoured. Subsections:
- **0.1 Always ask, never assume** — ambiguous contract shape, navigation flow, optionality, which layer
  owns a responsibility → stop and ask (`AskUserQuestion`). Never guess request/response shapes, token
  formats, error states.
- **0.2 Think hard, validate every assumption** — read the type, run the test, launch the simulator
  before *and* after. Memory describes the world when written; re-verify.
- **0.3 Every change updates `app/README.md`** — build/run/test/config changes update the README in the
  same change set.
- **0.4 DRY · KISS · SOLID, Swift-idiomatically** — one responsibility per type; depend on protocols;
  constructor injection only (no singletons/globals); value types + composition over inheritance; rule of
  three before abstracting; link the Swift API Design Guidelines.
- **0.5 Every change is runnable + testable locally** — the suite runs on any Mac/CI with **no backend**
  (mock-based); one command builds + tests.
- **0.6 Applying this guide to Cloak** — the non-negotiable invariants (E2EE, server untrusted with
  content; on-device AI only; privacy by design → most-restrictive default; minimal cleartext metadata;
  well-audited crypto, never custom) and how they bind each layer.

- [ ] **Step 3: Write §1 Architectural principles**

- **1.1 Layered modules + the dependency rule** — App → Feature → Data → Foundation; dependencies flow
  strictly downward; upper layers never imported by lower. One-paragraph rationale (testability,
  replaceability, holding a unit in context).
- **1.2 Protocol-defined seams** — every cross-layer boundary is a protocol; concretes injected at the
  composition root; the protocol is the mock seam for tests.
- **1.3 TDD is the inner loop** — red→green→refactor; Swift Testing against mocks; no implementation
  ahead of a failing test (full detail in §14).
- **1.4 Privacy/E2EE/on-device-AI invariants as architecture** — encryption boundary, on-device
  inference boundary, no-plaintext-logging are *structural*, not advisory.
- Include a small Mermaid graph of the layered package dependency direction (adapt the one currently in
  `app/CLAUDE.md`, modernised to the package names above).

- [ ] **Step 4: Write §2 Project layout & modularization** (the load-bearing section)

- **2.1 The SwiftPM package graph** — list the canonical packages (above) and the allowed import edges;
  state that the graph is the **compile-time enforcement** of §1.1 (the ArchUnit analogue: a
  `CloakFoundation*` package that tries to `import CloakFeature*` won't compile). Show a `Package.swift`
  dependency snippet for one Feature package depending on a Data + Foundation package and *not* able to
  reach a sibling Feature.
- **2.2 `Cloak<Layer><Feature>` naming** — the convention + examples.
- **2.3 File-suffix conventions — enforce these** — a table of the canonical suffixes (above) with the
  one responsibility each denotes; note SwiftLint custom rules / file-name rules enforce them (detail in
  §15).
- **2.4 What lives in each layer** — App (composition root + entry), Feature (a feature's screens +
  routing + view models), Data (repositories + persistence + transport + mappers), Foundation
  (cross-cutting, no business logic: Network, Encryption, Storage, Inference, Auth, Diagnostics,
  Utilities), OneUI (design system).
- **2.5 The composition root** — App assembles concrete implementations into the protocols and injects
  them down; nothing below App knows a concrete sibling. Show a tiny composition-root sketch.

- [ ] **Step 5: Verify (read-through)**

Re-read §0–§2 against spec sections "Decisions", "Opinionated stack", and the §0–§2 outline. Confirm:
(a) the package names + suffixes match the Canonical identifiers list exactly; (b) Swift/`Package.swift`
snippets are syntactically valid; (c) no `*Written in Task N.*` placeholders remain in §0–§2; (d) the
Mermaid graph parses (no syntax errors). Fix inline.

- [ ] **Step 6: Commit**

```bash
git add app/docs/ARCHITECTURE_GUIDE.md
git commit -m "$(printf 'docs(ios): architecture guide — foundations (§0–§2)\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 2: The app's shape (§3–§5)

**Files:** Modify `app/docs/ARCHITECTURE_GUIDE.md`

- [ ] **Step 1: Write §3 Presentation**

- **3.1 Dumb views** — SwiftUI views are declarative only: no business logic, no formatting, no
  networking, no persistence. Show a minimal `ConversationView` bound to a view model.
- **3.2 `@MainActor @Observable` view models — the pattern** — one view model per screen; `@Observable`
  (Observation framework), **not** `ObservableObject`/Combine and **not** the legacy Presenter; state is
  `private(set)`; intents are `async` methods. Show the canonical skeleton:
  ```swift
  @MainActor @Observable
  final class ConversationViewModel {
      private(set) var messages: [MessageDisplayModel] = []
      private let repository: MessageRepository
      init(repository: MessageRepository) { self.repository = repository }
      func onAppear() async { /* load + observe */ }
      func send(_ text: String) async { /* delegate to repository; optimistic update */ }
  }
  ```
- **3.3 Display models + mapping** — map domain/data models → view-ready `*DisplayModel` (formatting,
  localisation) outside the view and ideally outside the view model (a `*Mapper`). Keeps views and view
  models thin.
- **3.4 Navigation** — `NavigationStack` driven by an enum `*Route` per feature, owned by a feature
  `Router`; deep-linking via the enum; no UIKit coordinators. Show a `ConversationRoute` enum + stack
  binding sketch.
- **3.5 State, side effects, cancellation** — `.task {}` lifecycle, structured `async` intents, store
  long-lived streams in the view model and cancel on disappear.
- **3.6 Anti-patterns — refuse them** — logic/formatting/network in a `View`; massive view models;
  `ObservableObject`+Combine for new code; passing `NavigationPath` mutation into deep child views;
  business types leaking into views.

- [ ] **Step 2: Write §4 Concurrency**

- **4.1 Swift 6 strict concurrency** — language mode 6, **complete** checking; everything crossing an
  isolation boundary is `Sendable`; no `@unchecked Sendable` without a written justification.
- **4.2 Actors for stateful infrastructure** — the WebSocket connection, the Signal store, the storage
  layer, and the AI runtime are `actor`s (serialised mutable state). Show an `actor` skeleton for a
  connection owning its socket + outbox.
- **4.3 `@MainActor` for UI** — view models + view-facing types are `@MainActor`; hop off for I/O via
  `await` into actors; never block the main actor.
- **4.4 Structured concurrency** — prefer `async let` / task groups; child tasks tied to scope; store a
  cancellable `Task` only for long-lived streams; cancel in `deinit`/on disappear.
- **4.5 Pitfalls — refuse them** — unstructured `Task {}` that outlives its owner; `@unchecked Sendable`
  to silence warnings; blocking the main actor with sync work; capturing `self` strongly in a retained
  stream task.

- [ ] **Step 3: Write §5 Dependency injection & composition**

- **5.1 Constructor injection only** — dependencies are protocol-typed `init` parameters; no singletons,
  no globals, no service locators, no DI framework. Restate the rationale (testability, explicitness).
- **5.2 Protocol-per-boundary** — each Foundation/Data capability is a protocol; the concrete lives
  behind it; tests inject a fake.
- **5.3 The composition root** — `CloakApp` builds the object graph once and injects it down; show a
  composition-root building a `ConversationViewModel(repository: SQLiteMessageRepository(db:…))`.
- **5.4 SwiftUI wiring** — pass view models via `init` (or `@State` ownership at the screen root); use
  `@Environment` only for genuinely ambient values, not as a DI backdoor.
- **5.5 Test seams** — because every boundary is a protocol, the suite injects in-memory fakes (detail
  in §14).

- [ ] **Step 4: Verify (read-through)**

Re-read §3–§5. Confirm: `ConversationViewModel`, `MessageRepository`, `MessageDisplayModel`,
`*Route`/`Router` names are consistent with later/earlier usage and the Canonical list; all snippets are
valid Swift 6 (`@MainActor @Observable`, `actor`, `Sendable`); no placeholders; anti-pattern callouts
present in each section. Fix inline.

- [ ] **Step 5: Commit**

```bash
git add app/docs/ARCHITECTURE_GUIDE.md
git commit -m "$(printf 'docs(ios): architecture guide — presentation, concurrency, DI (§3–§5)\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 3: Data & the crypto/Signal boundary (§6–§7)

**Files:** Modify `app/docs/ARCHITECTURE_GUIDE.md`

- [ ] **Step 1: Write §6 Data layer**

- **6.1 Repository protocols** — the Data boundary is a set of repository protocols (e.g.
  `MessageRepository`, `ConversationRepository`) returning domain types; Features depend on the protocol,
  never on GRDB. Show a `MessageRepository` protocol (async CRUD + an `AsyncStream` of timeline updates).
- **6.2 GRDB + SQLCipher — the encrypted store** — whole-database AES-256 (SQLCipher) via GRDB; one
  `DatabaseQueue`/`DatabasePool` opened with a passphrase; concrete repositories live in `CloakData*`.
  Show opening an encrypted DB with a key from the keystore. **Restate why** (the Double-Ratchet rule,
  §7.4) in one sentence and cross-ref.
- **6.3 Schema, migrations, records** — `DatabaseMigrator` with versioned migrations; `Codable`
  GRDB records; record ↔ domain mapping via a `*Mapper`. Show a migration registering a `message` table.
- **6.4 Queries the messenger needs** — threads, pagination (keyset), unread counts, and **FTS5
  full-text search inside the encrypted store**. Show an FTS5 virtual-table migration + a search query.
- **6.5 Caching** — in-memory cache for hot data layered over the DB; the DB is the source of truth;
  offline-first reads.
- **6.6 Encryption-at-rest key handling** — the DB passphrase is a random 256-bit key created on first
  launch, stored in the Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, Secure-Enclave-
  gated where applicable); the store file is excluded from iCloud/iTunes backups; layered with
  `NSFileProtectionComplete`. Threat model in two lines (defends device theft/forensics/backup leak; not
  a live compromised unlocked device).
- **6.7 Anti-patterns** — GRDB types leaking above the repository; storing plaintext outside the
  encrypted DB; the DB key in `UserDefaults`/plist/source.

- [ ] **Step 2: Write §7 Cryptography & the Signal boundary**

- **7.1 `CloakFoundationEncryption`** — wraps libsignal; exposes domain operations (encrypt/decrypt a
  message, establish a session) and hides libsignal types behind the boundary.
- **7.2 `SignalProtocolStore` over the encrypted store** — implement libsignal's identity/prekey/signed-
  prekey/session stores backed by GRDB+SQLCipher (the §6 DB); identity/prekeys/sessions are domain
  types. Show the store-protocol surface (method signatures) and that persistence goes through a
  repository, not raw libsignal globals.
- **7.3 Key storage** — long-term identity private key + the DB key in the Keychain (Secure Enclave
  where possible); rotation/registration touchpoints (prekey replenishment) named as forward-looking.
- **7.4 The "decrypt once → persist plaintext" rule** — because the Double Ratchet deletes the message
  key on decrypt, the decrypted plaintext is persisted (encrypted-at-rest) immediately; re-decryption of
  the same ciphertext is impossible. This is *why* §6 exists. State it as a hard rule.
- **7.5 The encryption boundary invariant** — plaintext never crosses the boundary *outward* (to
  Network/Transport, logs, analytics, or off-device); only ciphertext leaves. No message content in any
  log/metric/crash report. Cross-ref §12.
- **7.6 Multi-device (forward-looking)** — name the boundary (per-device sessions, sender-key/multi-
  device fan-out) and defer detail to its slice; the abstraction (a session is per recipient *device*)
  is fixed now.
- **7.7 Anti-patterns** — rolling custom crypto; logging plaintext or keys; holding decrypted content in
  a type that also touches the network; storing keys outside the Keychain.

- [ ] **Step 3: Verify (read-through)**

Re-read §6–§7 against spec Decisions #4 + the §6/§7 outline + the privacy invariants. Confirm:
`MessageRepository`, `KeyStorage`, the SQLCipher-key flow, and the SignalProtocolStore surface are
internally consistent and consistent with §2/§5; the "decrypt once → persist plaintext" rule appears and
is cross-referenced from §6.2; every Swift snippet is valid; no plaintext-logging guidance contradicts
§12; no placeholders. Fix inline.

- [ ] **Step 4: Commit**

```bash
git add app/docs/ARCHITECTURE_GUIDE.md
git commit -m "$(printf 'docs(ios): architecture guide — data layer + Signal/crypto boundary (§6–§7)\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 4: Transport, on-device AI, Auth (§8–§10)

**Files:** Modify `app/docs/ARCHITECTURE_GUIDE.md`

- [ ] **Step 1: Write §8 Transport & delivery**

- **8.1 `MessageTransport` protocol — the mock seam** — the network boundary tests mock; the app never
  hits a live backend in tests. Show the protocol: `connect(accessToken:)`, `send(_:)`, an
  `AsyncStream<InboundEnvelope>` of incoming, `disconnect()`. Cross-ref the server contract.
- **8.2 The WebSocket actor** — an `actor` owns a `URLSessionWebSocketTask`, attaches the bearer token,
  runs the receive loop, and exposes the inbound stream. Show the connect + receive-loop skeleton.
- **8.3 Reconnection & backoff** — detect drop, exponential backoff with jitter, resume; surface
  connection state to the UI; idempotent re-subscribe.
- **8.4 Long-poll fallback — transparent degradation** — when the WS upgrade fails (proxies/network),
  fall back to long-poll behind the *same* `MessageTransport` protocol so Features are unaffected. State
  it as the graceful-degradation invariant (root CLAUDE.md principle 4).
- **8.5 Persisted offline outbox** — outbound messages are written to the encrypted store first, sent
  when connected, marked acked on receipt, and **replayed on reconnect**; ordering preserved;
  client-generated message ids give idempotency/dedupe.
- **8.6 Acks, receipts, ordering** — map to the server's delivery/receipt model; the message-status
  lifecycle is owned here + surfaced via §11.
- **8.7 Contract fixtures** — the wire envelope matches `docs/contracts/`; shared fixtures keep the mock
  transport honest (cross-ref §14).
- **8.8 Error surfacing** — transport errors are surfaced to the user clearly; **never silently drop a
  message**.
- **8.9 Anti-patterns** — Features importing the WebSocket concrete; plaintext on the wire; dropping
  sends on transient errors instead of queueing.

- [ ] **Step 2: Write §9 On-device AI boundary**

- **9.1 `LocalAssistant` protocol** — a narrow boundary: `respond(to prompt:…) -> AsyncStream<String>`
  (streamed tokens). Lives in `CloakFoundationInference`. Show the protocol.
- **9.2 Pluggable runtime** — the concrete runtime (Core ML / MLX / llama-style) sits behind the
  protocol; the boundary is fixed now, the runtime is chosen at its slice; swapping runtime never touches
  Features.
- **9.3 Lifecycle, memory & cancellation** — the model is an `actor`; explicit load/unload; a memory
  budget (the app must stay lean enough to bundle a model — root CLAUDE.md principle 5); cancellable
  generation.
- **9.4 Privacy — content never leaves the device** — inference is on-device only; no prompt/response
  content to any network, log, or analytics. Restate the on-device-AI invariant. Cross-ref §7.5, §12.
- **9.5 Anti-patterns** — any SDK call to a hosted LLM for content; logging prompts/responses; loading
  the model on the main actor.

- [ ] **Step 3: Write §10 Auth**

- **10.1 `AuthService` protocol** — `login() -> Tokens` / `validToken() -> String`; AppAuth OIDC-PKCE
  against Keycloak (`cloak-ios` client) behind it. Show the protocol + that the concrete is injected.
- **10.2 Token storage & refresh** — access/refresh tokens in the Keychain; refresh on expiry; attach
  the bearer to the transport (§8). Never log tokens.
- **10.3 Session lifecycle** — login, silent refresh, logout (clear Keychain + close transport), and the
  unauthenticated state.
- **10.4 Anti-patterns** — tokens in `UserDefaults`; passing tokens through view state; blocking UI on
  refresh.

- [ ] **Step 4: Verify (read-through)**

Re-read §8–§10. Confirm: `MessageTransport`, `LocalAssistant`, `AuthService` signatures are consistent
with their uses in §3/§5/§14; the graceful-degradation + offline-outbox + on-device-AI invariants are
stated; no plaintext/token/content logging guidance contradicts §7/§12; snippets valid; no placeholders.
Fix inline.

- [ ] **Step 5: Commit**

```bash
git add app/docs/ARCHITECTURE_GUIDE.md
git commit -m "$(printf 'docs(ios): architecture guide — transport, on-device AI, auth (§8–§10)\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 5: Cross-cutting (§11–§13)

**Files:** Modify `app/docs/ARCHITECTURE_GUIDE.md`

- [ ] **Step 1: Write §11 Error handling & resilience**

- **11.1 Typed errors per boundary** — each protocol declares a typed `Error`; map at boundaries
  (anti-corruption); no stringly-typed errors.
- **11.2 User-facing surfacing** — errors reach the user as clear, actionable state on the view model;
  transient transport errors degrade gracefully (retry/queue), not crash.
- **11.3 The message-status lifecycle** — Sending → Sent → Delivered → Read → Failed (matches the design
  system spec); where each transition is set (outbox/acks/receipts, §8). Show the enum.
- **11.4 Retry vs fail-fast** — retry transient transport/IO with backoff; fail-fast on programmer
  errors + auth failures; **never silently drop a message**.
- **11.5 Anti-patterns** — `try?` swallowing errors silently; forcing unwraps on remote data; surfacing
  raw library errors to the UI.

- [ ] **Step 2: Write §12 Client observability**

- **12.1 Structured logging** — `OSLog`/`Logger` with subsystem/category per module; **privacy
  redaction** (`%{public}` only for non-sensitive, `%{private}`/redaction by default); never message
  content, keys, tokens, or PII. Show a redacting `Logger` call.
- **12.2 Signposts & metrics** — `os_signpost` for performance-critical paths (transport, crypto,
  inference); client metrics carry no content/PII.
- **12.3 Crash & diagnostics** — `CloakFoundationDiagnostics`; crash reports scrubbed of content.
- **12.4 Mirrors server §10 intent** — correlation by message/trace id where available; the client never
  logs what the server is forbidden to.
- **12.5 Anti-patterns** — `print()` in shipping code; logging request/response bodies; content in
  analytics.

- [ ] **Step 3: Write §13 Design system (OneUI)**

- **13.1 The `CloakOneUI` package** — shared components + tokens; importable only by Feature + App; no
  business logic.
- **13.2 Tokens** — Royal & Spring palette, type scale, spacing, radii, the message-status visuals;
  light + dark. Link `docs/superpowers/specs/2026-06-10-cloak-design-system-design.md` as the source of
  truth (don't duplicate it — reference it).
- **13.3 Snapshot tests** — every OneUI component has a snapshot test (detail in §14).
- **13.4 Anti-patterns** — ad-hoc colours/fonts in Features; forking components; business logic in OneUI.

- [ ] **Step 4: Verify (read-through)**

Re-read §11–§13. Confirm: the message-status enum matches §8's lifecycle wording; logging-privacy
guidance is consistent with §7.5/§9.4; OneUI references the design-system spec rather than duplicating
it; snippets valid; no placeholders. Fix inline.

- [ ] **Step 5: Commit**

```bash
git add app/docs/ARCHITECTURE_GUIDE.md
git commit -m "$(printf 'docs(ios): architecture guide — errors, observability, OneUI (§11–§13)\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 6: Discipline & enforcement (§14–§18)

**Files:** Modify `app/docs/ARCHITECTURE_GUIDE.md`

- [ ] **Step 1: Write §14 TDD & testing strategy** (the discipline keystone — match server §12 depth)

- **14.1 Swift Testing is the inner loop** — `@Test`/`#expect` for logic/data/view-models; red→green→
  refactor; mock-based + hermetic — **no backend, no Testcontainers**. Contrast with the server
  (integration-against-real-infra) and state *why* the client mocks (isolation, speed, any-CI).
- **14.2 The test-type matrix** — a table: Swift Testing (logic/data/view-models, mocked deps),
  Contract checks (mock `MessageTransport`/`AuthService` honour the server contract via shared/recorded
  fixtures), XCUITest (navigation/interaction flows), Snapshot (OneUI). What each owns.
- **14.3 Mocks & fakes** — protocol fakes via constructor injection; an **in-memory encrypted GRDB** for
  data-layer tests; a fake `MessageTransport` with a controllable inbound stream. Show a fake transport.
- **14.4 Contract fixtures keep mocks honest** — fixtures copied/shared from `docs/contracts/`; refresh
  when the contract changes so mocks don't drift. (Cross-ref §8.7.)
- **14.5 Coverage gate** — `./app/scripts/coverage.sh` ≥90% on meaningful files; exclude
  `*View.swift`, app-entry, generated. (Cross-ref §15.)
- **14.6 Test naming** — `test_<subject>_<condition>_<expectedResult>` (or Swift Testing display names);
  one behaviour per test.
- **14.7 Pitfalls — refuse them** — asserting on mocks instead of behaviour; sleeping instead of awaiting;
  hitting a real network/file; UI tests doing logic assertions; snapshot tests with unstable inputs.

- [ ] **Step 2: Write §15 Tooling, build & quality gates**

- **15.1 XcodeGen + SwiftPM** — `project.yml` generates the app project (`.xcodeproj` git-ignored);
  local packages declared as SwiftPM dependencies; the package graph enforces layering (§2.1).
- **15.2 SwiftLint** — strict; custom rules + file-name rules enforcing the §2.3 suffixes; runs as a
  build phase (fails the build) + in CI.
- **15.3 Coverage gate** — the `xccov` script (≥90%, exclusions) runs in CI and locally.
- **15.4 CI** — lint → build → test → coverage; green required before review.
- **15.5 Config & secrets** — issuer/endpoint config per environment; no secrets in the repo; the
  Keychain holds runtime keys/tokens.

- [ ] **Step 3: Write §16 Security hardening**

- Keychain access controls + Secure Enclave; **TLS/cert pinning** for the WebSocket + token endpoints;
  no plaintext logging (cross-ref §12); sensitive stores excluded from backups (cross-ref §6.6);
  pasteboard/screenshot considerations for sensitive screens; jailbreak-awareness where it earns its
  keep. Keep it a tight, enforce-able checklist.

- [ ] **Step 4: Write §17 Anti-patterns to refuse (consolidated)**

A single scannable list pulling the per-section "refuse them" items into one place (cross-layer import /
logic in views / ObservableObject-for-new-code / plaintext outward / custom crypto / hosted-LLM for
content / tokens in UserDefaults / silent message drops / asserting on mocks …). Each one line.

- [ ] **Step 5: Write §18 New-feature bootstrap checklist** (mirror server §16)

An ordered checklist for adding a feature: pick the layer/package, define the protocol seam(s), write the
failing Swift Test against a fake, implement behind the protocol, wire at the composition root, add the
view + view model (+ OneUI), snapshot/UI test, update `app/README.md`, run lint+coverage, open the PR.

- [ ] **Step 6: Verify (read-through)**

Re-read §14–§18 + do a **whole-document consistency pass**: every `*Written in Task N.*` placeholder is
gone; the section index (if any) matches the headers; every canonical identifier is used consistently
across all 18 sections; the consolidated §17 list matches the per-section anti-patterns; coverage/lint/CI
claims in §14–§15 agree with the Phase 0 iOS plan's gates. Fix inline.

- [ ] **Step 7: Commit**

```bash
git add app/docs/ARCHITECTURE_GUIDE.md
git commit -m "$(printf 'docs(ios): architecture guide — testing, tooling, security, anti-patterns (§14–§18)\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 7: Reduce `app/CLAUDE.md` to a pointer + README + final sweep

**Files:** Modify `app/CLAUDE.md`, `app/README.md`

- [ ] **Step 1: Rewrite `app/CLAUDE.md` as a short pointer** (mirror `server/CLAUDE.md`)

Replace the body with: a one-paragraph "what this is"; a **"read `docs/ARCHITECTURE_GUIDE.md` end-to-end
before feature work"** instruction; the non-negotiable Cloak invariants (E2EE, on-device AI, privacy,
minimal metadata); the operating principles in brief; and a **section-index table** (Need → Section)
pointing into the guide — exactly the shape of `server/CLAUDE.md`'s "Where to find it" table. Remove the
old 5-layer prose now that the guide owns it.

- [ ] **Step 2: Update `app/README.md`**

Add a one-line link to `docs/ARCHITECTURE_GUIDE.md` under an "Architecture" heading. Only touch run/test
sections if they changed (they did not in this docs-only change).

- [ ] **Step 3: Final cross-document sweep**

Confirm: `app/CLAUDE.md`'s index table targets real `## N.` headers in the guide; the guide's preamble
references `app/CLAUDE.md` for the invariants summary (no contradiction); the design-system + Phase 0 iOS
plan references resolve; no broken relative links. Read the guide top-to-bottom once for tone/flow.

- [ ] **Step 4: Commit**

```bash
git add app/CLAUDE.md app/README.md docs/superpowers/plans/2026-06-13-ios-architecture-guide.md
git commit -m "$(printf 'docs(ios): point CLAUDE.md at the architecture guide; README link\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

## Definition of Done (this plan)

- `app/docs/ARCHITECTURE_GUIDE.md` exists with all 18 sections written at server-guide depth — opinionated
  prose, valid Swift examples using the canonical identifiers, and per-section "refuse them" anti-patterns.
- Every settled decision in the design spec is reflected: modernized layering (`@Observable`, drop
  Presenter, Swift Testing), SwiftPM compile-enforced module boundaries, GRDB+SQLCipher encrypted-at-rest,
  the crypto/transport/AI/auth boundaries, and the privacy invariants.
- `app/CLAUDE.md` is a short pointer (invariants + index table) deferring to the guide; the old 5-layer
  prose is removed; `app/README.md` links the guide.
- No `*Written in Task N.*` placeholders, no TODOs; canonical identifiers consistent across all sections.
- Branch ready for PR per root `CLAUDE.md` (squash + both reviews).

## Notes / deferred

- **No build/test gate** runs here (docs-only). The guide *documents* the gates; it does not wire them —
  the XcodeGen/SwiftLint/coverage tooling lands in the Phase 0 iOS skeleton execution.
- **Phase 0 iOS plan refresh (XCTest → Swift Testing)** is a separate follow-up, flagged in the spec, not
  done here.
- Swift snippets are illustrative and verified by reading (no Xcode project yet); they must still be
  syntactically valid and identifier-consistent.
