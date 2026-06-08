# Flyway migrations

The single source of truth for Cloak's PostgreSQL schema.

## Naming

- Versioned: `V{n}__{snake_case_description}.sql` — e.g. `V1__baseline_message_and_device.sql`
- Repeatable (views/functions, re-run when changed): `R__{name}.sql`

## Rules

- **Forward-only and immutable.** Never edit a merged migration — add a new one.
- **No destructive change without a plan.** Drops, renames, and type-narrowing land only alongside an explicit migration plan.
- **Ciphertext only.** No column, index, or generated value may derive from message plaintext. Reference users by Keycloak `sub`; no PII.
- **TDD.** A migration exists because a failing server integration test (Testcontainers) needs it — not "just in case."

The first migration is added during the walking-skeleton phase (see the initiative plan), driven by the first end-to-end test.
