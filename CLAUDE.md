# Cloak

A privacy-first, end-to-end encrypted messaging app. The defining feature is that all AI assistance runs locally on-device via a lightweight model (Gemma 3N or equivalent), so no user data is ever sent to a hosted LLM.

## Repository layout

```
Cloak/
├── app/        # iOS client (Swift / SwiftUI)
├── server/     # Backend (Java 25, Spring Boot 4)
├── db/         # PostgreSQL schema, migrations (Flyway)
├── queue/      # Kafka configuration and topic definitions
└── iam/        # Keycloak realm configuration (identity & access management)
```

Each subdirectory has its own `CLAUDE.md` with component-specific guidance.

## Architecture

### Real-time transport
- **Primary:** WebSockets for full-duplex messaging
- **Fallback:** Long-polling when WebSocket upgrade fails (network restrictions, proxies)

### Security model
- **End-to-end encryption (E2EE):** Messages are encrypted on the sender's device and can only be decrypted by the recipient. The server never holds plaintext.
- **On-device AI:** Inference runs locally on iOS using Gemma 3N (or a similar model). No message content or user data is sent to a remote LLM.
- **Identity & access management (IAM):** Authentication is delegated to **Keycloak** over OpenID Connect (OAuth 2.0). Keycloak owns user accounts and credentials and issues JWT access tokens; the server validates those tokens but never manages passwords. Keycloak handles identity only — it never sees message content, and E2EE keeps the IAM layer outside the trust boundary for message confidentiality.

### Components
| Component | Technology | Role |
|-----------|-----------|------|
| iOS app | Swift / SwiftUI | Client UI, local AI inference, E2EE crypto |
| Server | Java 25, Spring Boot 4 | WebSocket hub, token-validated auth, message routing |
| IAM | Keycloak | Identity & access management — user accounts, authentication, OIDC/OAuth 2.0 token issuance |
| Database | PostgreSQL | Persistent storage for encrypted message history and public key registry |
| Queue | Kafka | Reliable async delivery, fan-out to offline/multi-device recipients |

## README maintenance

After every change, update the README.md in:
- The root `Cloak/` folder — project overview, architecture summary, and how to run all components together
- The affected component folder(s) — component-specific description and local development run instructions

READMEs should be concise: what the component is, how to run it locally, and any prerequisite setup. No marketing copy, no exhaustive API docs.

## Key principles

1. **Privacy by design.** Never log, store, or forward plaintext. If a decision touches user data, default to the most restrictive option.
2. **E2EE is non-negotiable.** The server is untrusted with respect to message content. Encryption/decryption happens at the client only.
3. **On-device AI.** Feature work involving AI must keep inference local. Do not introduce SDK calls to hosted AI providers for anything that touches message content.
4. **Graceful degradation.** WebSocket → long-poll fallback must be transparent to the user. Design message delivery so it is reliable across both transports.
5. **Minimal footprint.** Prefer lightweight dependencies. The iOS app must stay lean enough to bundle a local model.
6. **Minimal cleartext metadata.** Only the metadata strictly required to route and deliver a message may travel or be stored outside the encryption envelope, and it is kept to the absolute minimum. Any metadata not needed to send or receive a message is encrypted together with the message body. Every cleartext field must be justified against this rule — when in doubt, encrypt it or ask for clarification.

## Cross-cutting concerns

- **Encryption primitives:** Use well-audited libraries (e.g. Signal Protocol / libsodium on iOS, Bouncy Castle on the server). Do not roll custom crypto.
- **Key management:** Public key distribution goes through the server; private keys never leave the device.
- **Testing:** On the **server**, integration tests use real transport and infrastructure (Testcontainers, no mocked sockets) to catch protocol-level regressions. The **iOS app** instead mocks its dependencies (mock `Service` / network, in-memory fakes) so its suite needs no running backend — see `app/CLAUDE.md`. Contract fixtures keep those mocks aligned with the real server contract.
- **Error handling:** Surface transport errors to the user clearly; never silently drop messages.
- **Code clarity over comments:** Prefer self-explanatory code — clear names and small, focused functions — so the code reads on its own. Add a comment only when it says something the code cannot: the *why* behind a non-obvious decision, a subtle invariant or gotcha, or a pointer to an external contract/spec. Don't restate what the code already shows or narrate the obvious (constructors, getters, trivial delegations, one-line wiring) — such comments are noise; leave them out. The same test governs API docs (Javadoc/DocC): document intent and contracts, not mechanics. When a comment only describes *what* the code does, delete it and let the names carry the meaning.

## Engineering workflow & quality gates

These apply to **all work in this repo, indefinitely** — not only the MVP.

**Branch & PR flow.** Work on a `feature/<name>` branch off `main`. Before opening a PR, **rebase onto the latest `main` and squash into a single commit** whose message is a rewritten, consolidated summary of the whole change — never a concatenation of work-in-progress commits.

**Review before merge — both required.** Nothing merges to `main` until **both** pass:
1. **Skill-based code review** of the PR diff (`/code-review`, or `superpowers:requesting-code-review`), with findings addressed.
2. **Manual human review** and approval.

The skill review never replaces the manual one.

**Quality gates — the build fails if any fail.**
- **TDD** red → green → refactor on every change (details in the component `CLAUDE.md`s).
- **Test coverage ≥ 90%**, predominantly integration tests, measured on meaningful code (generated, config, and pure-view code excluded from the denominator). JaCoCo on the server; Xcode coverage on iOS.
- **Linting passes:** Checkstyle with Google standards on the server; SwiftLint on iOS.
