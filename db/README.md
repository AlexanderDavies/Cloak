# Cloak — Database

PostgreSQL 16, run as a Docker container. System of record for **encrypted message history** and the **public-key / device registry**. Stores ciphertext only — the database never holds plaintext. Identity lives in Keycloak (`../iam/`); users are referenced by their Keycloak `sub`.

## Prerequisites

- Docker

## Run locally

From the repo root (starts the whole infra stack and applies migrations):

```bash
./dev.sh up
```

Or DB-only, from this folder:

```bash
docker compose up -d   # starts Postgres on localhost:5432 and runs Flyway migrations
docker compose down    # stop (add -v to also wipe the data volume)
```

Connection (local dev): `postgresql://cloak:cloak@localhost:5432/cloak`

## Migrations

Flyway SQL lives in `migrations/`, named `V{n}__{description}.sql`. It is the single source of truth for the schema; the server consumes the same scripts. Migrations are forward-only — fix-forward with a new file, never edit a merged one.

| Migration | Description |
|-----------|-------------|
| `V1` | Baseline — `encrypted_message` history table + `device` registry |
| `V2` | Slice 1 — Signal prekey registry: `signed_prekey` and `one_time_prekey` (with `consumed_at` for atomic X3DH consumption) |
| `V3` | Slice 2 — `kyber_prekey` table for ML-KEM-1024 (PQXDH last-resort Kyber key per device) |
| `V4` | Slice 3 — `encrypted_message` gains `conversation_id`, `sender_device_id`, `recipient_device_id`, `message_type`; drops the always-null `device_id`. **Destructive:** truncates `encrypted_message` first (see below) |

**V4 truncates `encrypted_message`.** The new columns are `NOT NULL` with no default, which cannot be added to a table that already holds rows. Pre-release this is safe and deliberate: the table is a delivery queue of ciphertext, and decrypt-once means a row already delivered can never be decrypted again — there is nothing to backfill and nothing of value to keep. A developer database that predates this branch loses its queued ciphertext, not any readable history (local device history is authoritative).

**One-time prekey consumption:** `GET /v1/keys/{sub}` atomically claims one row from `one_time_prekey` by setting `consumed_at`. Available keys have `consumed_at IS NULL`; consumed ones are retained for auditability.

## Architecture & conventions

See [`CLAUDE.md`](CLAUDE.md) for the schema overview, privacy guardrails (ciphertext-only, minimal metadata), and migration rules.
