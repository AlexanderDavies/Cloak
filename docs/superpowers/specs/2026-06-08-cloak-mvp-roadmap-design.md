# Cloak MVP — Feature Roadmap Design

- **Date:** 2026-06-08
- **Status:** Approved (design); pending implementation planning
- **Branch:** `feature/planning`
- **Scope:** Decomposition and sequencing of the Cloak MVP into vertical feature slices. This is a roadmap/decomposition spec — each slice gets its own detailed implementation plan when it is started.

## Context

Cloak is a privacy-first, end-to-end encrypted (E2EE) messaging app: iOS client (Swift/SwiftUI), Spring Boot server, Keycloak IAM, PostgreSQL, and Kafka. The server is untrusted with message content; encryption/decryption happen only on-device. (See root `CLAUDE.md` and the per-component `CLAUDE.md` files for architecture and principles.)

This spec breaks the MVP into vertical "steel thread" slices so integration risk is paid down continuously and `main` stays shippable after every slice.

## Locked decisions

| Decision | Choice |
|----------|--------|
| **MVP boundary** | Core 1:1 messaging: Keycloak auth, 1:1 E2EE text, message history, offline delivery + multi-device fan-out |
| **E2EE foundation** | Signal Protocol (X3DH + Double Ratchet) via **libsignal** (official library). No custom crypto. |
| **MVP edges (in)** | WebSocket transport + long-poll fallback, APNs push notifications, delivery & read receipts |
| **MVP edges (out / TBC)** | Media attachments — TBC (future) |
| **Auth validation** | JWKS-based **local** JWT validation, not introspection (rationale + mechanics in `iam/CLAUDE.md`) |
| **Resourcing** | Solo (developer + Claude) → thin, strictly-sequential slices, minimal WIP |
| **Execution strategy** | Walking skeleton first, then capability-thin vertical slices (Approach A) |
| **UX handling** | Per-slice screen inventory; login is Keycloak-hosted; detailed visuals per-slice via the OneUI design system |

## Execution strategy

Vertical steel threads, after one walking skeleton. Each slice:

- adds exactly **one** capability end-to-end,
- is independently shippable and leaves `main` green,
- is built **contract-first, then TDD on both sides**.

## Phase 0 — Walking skeleton

**Goal:** prove the entire pipe end-to-end across every component, with no real features, so later slices plug into known-good plumbing.

- **iOS:** Keycloak login (OIDC PKCE via `ASWebAuthenticationSession`) → JWT. One screen: text field + send + message list. **libsignal is linked and generates a device identity key pair on first launch** (de-risks the dependency without needing full sessions yet).
- **Payload:** sent as an **opaque ciphertext blob** — real X3DH/ratchet sessions arrive in Slices 2–3. The skeleton proves transport treats payloads as opaque bytes; it does *not* yet prove real E2EE.
- **Server:** validates the Keycloak JWT (resource server, `aud: cloak-api`) → authenticated WebSocket → accepts envelope `{toSub, fromSub, deviceId, ciphertext}` → persists ciphertext to Postgres → publishes to `cloak.messages.outbound` keyed by recipient `sub` → consumer pushes it to the recipient's connected WebSocket session.
- **DB:** first Flyway migration (`V1`) — minimal `device` + `encrypted_message` tables.
- **Kafka:** producer + consumer wired against the defined topics.
- **Seed users:** 1–2 test users seeded in `cloak-realm.json` so login is the real OIDC flow without building onboarding UI yet.

**Folded into Phase 0 (carry-over items):**
1. Wire the server's Flyway + Testcontainers to consume `db/migrations` as the single source of truth (open item from `db/CLAUDE.md`).
2. Stand up the `docs/contracts/` seam (client↔server contract artifacts; see Cross-cutting).
3. Establish the **quality gates** so every later slice inherits them: linting wired to **fail the build** — Checkstyle with Google standards on the server, SwiftLint on iOS — and coverage tooling (JaCoCo on the server, Xcode coverage on iOS) enforcing the **≥90%** threshold. Phase 0's own code must pass these gates.

**Acceptance:** two app instances (User A, User B) both logged in; A sends "hello" → B receives and displays the blob — traversing Keycloak → server → Postgres → Kafka → WebSocket → recipient, with libsignal linked on iOS.

**Tests:** server integration test on Testcontainers (Postgres + Kafka + Keycloak) for the round trip; iOS integration test against a mocked `Service`; shared contract fixture for the envelope.

## MVP slices

Each slice lists *Goal*, *Screens*, *Touches*, and *Done* (acceptance).

### Slice 1 — Account onboarding + device key registration
- **Goal:** user self-registers (Keycloak-hosted) and logs in; app generates identity key + signed prekey + one-time prekeys via libsignal and publishes the **public** bundle; server stores it in the device/prekey registry.
- **Screens:** Keycloak register/login (hosted), first-run "setting up secure keys" screen, empty conversation list.
- **Touches:** app (keygen, Foundation/Encryption + Data), server (prekey upload, device registry), db (prekey tables migration), iam (enable self-registration).
- **Done:** new user has a device row + queryable public prekey bundle; private keys stored encrypted on-device.

### Slice 2 — X3DH session + first real encrypted message  *(retires the opaque blob)*
- **Goal:** sender fetches recipient's bundle, runs X3DH, sends a real encrypted message; recipient establishes the session and decrypts.
- **Screens:** start-conversation (user lookup by username/email), chat thread (compose + bubbles).
- **Touches:** app (X3DH, session store), server (bundle-fetch endpoint that consumes a one-time prekey), db (mark prekey consumed).
- **Done:** two real users exchange a genuinely E2EE message; server sees only ciphertext.

### Slice 3 — Double Ratchet conversation + history
- **Goal:** full back-and-forth with per-message ratcheting; persist and restore history; tolerate out-of-order.
- **Screens:** chat thread with scrollback; conversation list with locally-decrypted last-message preview.
- **Touches:** app (ratchet persistence, local encrypted history), server (ciphertext history pagination), db.
- **Done:** multi-message convo with forward secrecy; relaunch restores history; out-of-order handled.

### Slice 4 — Offline delivery + deliver-on-reconnect
- **Goal:** messages to an offline recipient are durably queued and delivered, in order, on reconnect — with **effectively-once** semantics (Kafka at-least-once + idempotent dedupe on message id, per `queue/CLAUDE.md`).
- **Screens:** connection-status indicator (minor).
- **Touches:** server (delivery tracking/cursor), queue (per-device consumer, replay), db (delivery state), app (reconnect + drain).
- **Done:** send to offline user → they reconnect → receive all pending in order, no visible duplicates.

### Slice 5 — Multi-device + self-sync
- **Goal:** per-device keys/sessions; sending fans out to all of a user's devices; your own devices get copies of what you send.
- **Screens:** linked-devices screen; add-second-device flow.
- **Touches:** app (per-device sessions, sync), server (device-list resolution, per-device fan-out), queue, db.
- **Done:** log in on device 2 → conversations sync; messages reach all devices.

### Slice 6 — Long-poll fallback
- **Goal:** transparent fallback when WS upgrade fails, same delivery semantics.
- **Screens:** none (transparent).
- **Touches:** server (long-poll endpoints), app (transport abstraction + auto-fallback in Foundation/Network).
- **Done:** with WS blocked, messaging still works; switching transports never drops/dupes.

### Slice 7 — APNs push notifications
- **Goal:** silent/encrypted push wakes the app to fetch; no plaintext in payload; local notification after decrypt.
- **Screens:** permission prompt, system notifications, notification settings.
- **Touches:** app (APNs registration, push handling in Non-UI layer), server (APNs sender, push tokens), db (token storage).
- **Done:** backgrounded device gets push → fetches/decrypts → shows local notification; payload has no plaintext.

### Slice 8 — Delivery & read receipts
- **Goal:** per-message delivered/read status over the receipts topic.
- **Screens:** sent/delivered/read indicators in the chat thread.
- **Touches:** app (emit/consume receipts + UI states), server (receipt routing), queue (receipts topic), db.
- **Done:** sender sees delivered → read as recipient receives/opens.

### Slice 9 — Prekey replenishment + key-rotation hardening
- **Goal:** replenish one-time prekeys before they run out; rotate the signed prekey; handle device revocation.
- **Screens:** minor (mostly background; small devices/security refinement).
- **Touches:** app (background replenish + rotation), server (prekey-count reporting, rotation, revocation), db.
- **Done:** prekeys never exhaust under load; rotated signed prekey accepted; revoked device can't receive.

## Cross-cutting (how every slice is run)

**Per-slice workflow (solo).** Each slice is a `feature/<slice-name>` branch off `main`, following the repo-wide **Engineering workflow & quality gates** in root `CLAUDE.md` (squash + rebase with a consolidated message before PR → skill-based code review **and** manual review → merge → delete branch). Each slice gets its own detailed implementation plan (writing-plans skill) when started.

**Inside a slice — contract-first, then TDD both sides:**
1. Define the interface (envelope/endpoint/topic contract).
2. **Server** — red/green on `@SpringBootTest` Testcontainers tests (Postgres + Kafka + Keycloak) + domain unit tests; Flyway migration added here.
3. **iOS** — red/green on integration tests against a **mocked** `Service`, driven by the shared contract fixture so the mock can't drift.
4. Wire end-to-end and verify acceptance on **two simulators**.

**Contract artifact.** Client↔server contracts (REST/WS envelopes, Kafka record shapes) live in one source of truth: `docs/contracts/` at the root, with the server as the authority and iOS fixtures copied/generated from it. This is the seam that keeps the iOS mocks honest (iOS does not use Testcontainers).

**Definition of Done (every slice):**
- Meets the repo-wide **Engineering workflow & quality gates** in root `CLAUDE.md` (squash + rebase, skill **and** manual review, ≥90% coverage predominantly integration, linting).
- Tests green both sides; acceptance criteria demonstrated on two simulators.
- **Privacy gate verified for this slice's data:** no plaintext in Postgres, logs, or Kafka; private keys never leave the device; only minimal routing metadata is cleartext — any non-routing metadata is encrypted (root principle 6).
- READMEs updated (per-component rule); full stack still comes up via `./dev.sh up` + `./gradlew bootRun` + Xcode.
- Merged to `main`; branch deleted.

**Known-hard parts (risk register):** libsignal iOS linkage (Phase 0), exactly-once offline delivery (Slice 4), multi-device sessions/sync (Slice 5), E2EE-safe APNs (Slice 7).

## TBC (future)

Not in MVP; to be confirmed and scoped as their own future initiatives (each its own brainstorm → spec → plan):

- Group chat
- On-device AI assistance (Gemma 3N or equivalent)
- Media attachments (encrypted images/files)
- Android / web clients

## Next step

Hand off to the writing-plans skill to produce the first implementation plan — **Phase 0 (walking skeleton)** — then proceed slice by slice.
