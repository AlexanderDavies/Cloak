# CLAUDE.md — Cloak Server

Spring Boot 4 / Java 25 backend: WebSocket hub, Keycloak-backed auth (validates OIDC/OAuth 2.0 JWTs — Keycloak is the IAM and owns accounts/credentials), encrypted-message routing, Kafka delivery events, PostgreSQL persistence. The server is **untrusted with message content** — it never holds or logs plaintext (see root `Cloak/CLAUDE.md` for the E2EE/privacy/on-device-AI invariants).

## Architecture guide — read before feature work

The full engineering guide (Hexagonal + DDD + BDD under mandatory TDD) lives in **[`docs/ARCHITECTURE_GUIDE.md`](docs/ARCHITECTURE_GUIDE.md)**.

**Before scaffolding a service, building a feature, or changing any adapter/use case/event processor, read `docs/ARCHITECTURE_GUIDE.md` end-to-end.** It is the source of truth for patterns, file-suffix conventions, the integration-test harness, and the anti-patterns list. The summary below does not replace it.

## Operating principles — apply on every cycle (guide §0)

These are behaviours, not architecture. They govern every task regardless of whether the full guide is open.

1. **Ask, never assume (§0.1).** Ambiguous requirement, undocumented integration shape, or a pattern that doesn't clearly cover the case → stop and ask via `AskUserQuestion`. Don't guess request/response shapes, nullability, topic names, error codes, auth model, or retention.
2. **Validate every assumption (§0.2).** Read the code, run the query, check the config before writing. Re-verify after — run the test, hit the endpoint, inspect the row. Memory describes the world when it was written; confirm it still holds.
3. **Every change updates the README (§0.3).** Any change to how the service is built, run, configured, tested, deployed, or integrated updates the README in the same change set. Re-read it before declaring done.
4. **DRY · KISS · SOLID (§0.4).** Single source of truth; simplest design that meets the requirement; rule of three before abstracting; constructor injection only; new behaviour via new types, not edits to existing ones.
5. **Runnable locally (§0.5).** One command brings the service up against Testcontainers/WireMock — never shared cloud. If a change breaks local run, add a containerised stand-in alongside it.

## TDD is mandatory (guide §12)

Red → green → refactor on every feature, bug fix, and behaviour-affecting config change. The inner loop is **`@SpringBootTest` integration tests on Testcontainers + WireMock** — not mock-heavy unit tests. Domain logic also gets pure unit tests. Karate BDD (§13) sits downstream as living documentation, never as the TDD substitute.

## Where to find it in the guide

| Need | Section |
|------|---------|
| **Cloak domain mapping + privacy guardrails (ciphertext-only, no plaintext logging)** | **§0.6** |
| Layout, package naming, file-suffix conventions, ArchUnit boundary | §2 |
| Virtual-threads concurrency model + pitfalls | §3 |
| Aggregates, factories, value objects, `DomainClock` | §4 |
| Use cases, commit helper (persist-then-publish), mappers | §5 |
| Ports & adapters, controller pattern | §6 |
| Retries, **circuit breakers & outbound-HTTP resilience**, optimistic locking, transaction boundary | §7 |
| Domain event orchestration | §8 |
| Error handling, global exception handler | §9 |
| Observability (correlation, logging, metrics) | §10 |
| Typed config, feature toggles, profiles | §11 |
| Integration-test harness (singleton containers, reset, WireMock) | §12 |
| Karate BDD, fixtures, chaos | §13 |
| Anti-patterns to refuse | §15 |
| New-service bootstrap checklist | §16 |
