# CLAUDE.md — Cloak Database

PostgreSQL is Cloak's system of record for **encrypted message history** and the **public-key / device registry**. It runs as a Docker container for local development. Like the server, the database is **untrusted with message content**: every payload is stored as ciphertext exactly as the client produced it. Plaintext is never written, and nothing in a row, index, or log may reveal message contents (see root `Cloak/CLAUDE.md` for the E2EE/privacy invariants).

Identity lives in **Keycloak** (see `iam/`). Postgres does **not** store accounts, credentials, or profile data — it references users only by their Keycloak subject (`sub`).

## What lives in this folder

| Path | Purpose |
|------|---------|
| `docker-compose.yml` | Local Postgres 16 container + a Flyway sidecar that applies migrations on startup |
| `migrations/` | Flyway SQL migrations — **the single source of truth for the schema** |
| `README.md` | How to run the database locally |
| `CLAUDE.md` | This guide |

## Schema overview

The schema will include, at minimum, the tables below. Exact columns are finalised by a failing server integration test during the walking-skeleton phase (TDD) — treat this as the shape, not the final DDL.

- **`device`** — the public-key / device registry. One row per device key.
  `id` (uuid, pk), `owner_sub` (text, the Keycloak `sub`), `public_key` (bytea), `algorithm` (text), `created_at` (timestamptz), `revoked_at` (timestamptz, null = active).
- **`encrypted_message`** — encrypted message history.
  `id` (uuid, pk), `sender_sub` (text), `recipient_sub` (text), `conversation_id` (uuid), `ciphertext` (bytea), `created_at` (timestamptz).

There are **no plaintext columns**. The server reads/writes ciphertext only; decryption happens exclusively on-device.

## Privacy guardrails — non-negotiable

1. **Ciphertext only.** Message bodies are `bytea` ciphertext. Never add a column, index, or generated value derived from plaintext.
2. **No plaintext in logs.** `log_statement` must not capture payloads in any environment. Keep query logging off for the message tables.
3. **Minimise metadata.** Timestamps, sizes, and sender/recipient links leak a social graph even without plaintext. Add a metadata column only when a feature truly needs it, and document why.
4. **Reference identity, don't copy it.** Users are identified by Keycloak `sub`. No emails, display names, passwords, or other PII in Postgres.
5. **Retention.** Define a retention/expiry policy per table (default to the shortest that satisfies the feature). Most-restrictive wins when unsure.

## Migration conventions (Flyway)

- Versioned: `V{n}__{snake_case_description}.sql` (e.g. `V1__baseline_message_and_device.sql`). Repeatable views/functions: `R__{name}.sql`.
- **Forward-only and immutable.** Once a migration is merged it is never edited — fix-forward with a new migration.
- **No destructive change without a plan.** Drops/renames/type-narrowing land only alongside an explicit migration plan in the same change set.
- `migrations/` is the **single source of truth**. The server's Flyway (boot + Testcontainers in tests) must consume *these* scripts — do not duplicate DDL under `server/`. Wiring the server to this location is a plan task; until then, treat this folder as canonical.

## Operating principles — apply on every cycle

Same principles as the rest of Cloak (`server/CLAUDE.md` §0):

1. **Ask, never assume.** Unclear column nullability, type, key shape, retention, or ownership → stop and ask via `AskUserQuestion`. Never guess the schema.
2. **Validate every assumption.** Run the migration against a clean container and inspect the result before declaring done.
3. **TDD.** Schema changes are driven by a failing server integration test (Testcontainers), then made green. No DDL written "just in case."
4. **DRY · KISS · SOLID.** One source of truth for the schema (this folder); the simplest tables that satisfy the feature; no speculative columns or indexes.
5. **Every change updates the README.** Any change to how the DB is run, configured, or migrated updates `db/README.md` in the same change set.

## Local run

See `README.md`. In short: `../dev.sh up` (from repo root) starts Postgres and applies migrations; or `docker compose up -d` from this folder for DB-only iteration.
