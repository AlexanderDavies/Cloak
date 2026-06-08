# CLAUDE.md — Cloak Queue (Kafka)

Kafka is Cloak's asynchronous delivery backbone: it carries encrypted message payloads for **reliable async delivery and fan-out to offline and multi-device recipients**. It runs as a single-node Docker container (KRaft mode) for local development. Like every server-side component, the queue is **untrusted with message content** — only ciphertext flows through it, and topic names, keys, and headers must never reveal message contents (see root `Cloak/CLAUDE.md` for the E2EE/privacy invariants).

## What lives in this folder

| Path | Purpose |
|------|---------|
| `docker-compose.yml` | Local single-node Kafka (KRaft) container |
| `create-topics.sh` | **Single source of truth for topic definitions.** Idempotent; creates topics via the running broker |
| `README.md` | How to run Kafka locally |
| `CLAUDE.md` | This guide |

## Topics

Naming convention: `cloak.<domain>.<event>`. Defined in `create-topics.sh`.

| Topic | Key | Purpose |
|-------|-----|---------|
| `cloak.messages.outbound` | recipient `sub` (userId) | Primary delivery stream — encrypted payloads fanned out to a recipient's offline/multi-device endpoints |
| `cloak.messages.receipts` | recipient `sub` | Delivery/read receipts flowing back toward senders |
| `cloak.messages.dlq` | original key | Dead-letter for payloads that exhausted delivery retries |

### Keying & ordering

- **Partition by recipient user (`sub`).** This is the chosen model: it makes fan-out to all of a user's devices natural and preserves **per-recipient ordering**. A consumer for a given user always reads that user's stream in order.
- Multi-device fan-out is a downstream concern: one keyed record per recipient user; the consumer resolves and delivers to each active device/session.
- Do **not** key by a value derived from plaintext.

### Delivery semantics

- **At-least-once.** Consumers must be **idempotent** (dedupe on message id) — duplicates are expected, lost messages are not.
- Producers persist-then-publish (the server's commit helper, guide §5) so the DB row and the Kafka record never diverge.
- Failed deliveries route to `cloak.messages.dlq` after the retry policy is exhausted; never silently drop.

### Local defaults

- Replication factor **1**, **12** partitions for message topics (1 for the DLQ). These are local-dev values; production replication/partitioning is set per environment, not here.

## Privacy guardrails — non-negotiable

1. **Ciphertext payloads only.** Record values are the client's ciphertext. Nothing plaintext in values, keys, headers, or topic names.
2. **Minimise metadata.** Keys and headers leak a social graph and timing. Carry only what delivery requires (recipient `sub`, message id).
3. **No plaintext logging.** Consumer/producer logs must never dump record values.
4. **Retention.** Set the shortest retention that satisfies reliable delivery; most-restrictive wins when unsure.

## Operating principles — apply on every cycle

Same principles as the rest of Cloak (`server/CLAUDE.md` §0):

1. **Ask, never assume.** Unclear topic name, key, partition count, ordering need, or retention → stop and ask via `AskUserQuestion`. Never invent a topic contract.
2. **Validate every assumption.** Bring the broker up, create the topic, produce/consume a record before declaring done.
3. **TDD.** Topic/consumer behaviour is driven by a failing server integration test against **real Kafka (Testcontainers)** — not mocked producers. Mirrors the "real transport in integration tests" rule.
4. **DRY · KISS · SOLID.** `create-topics.sh` is the one place topics are defined; the simplest topology that satisfies delivery; no speculative topics or partitions.
5. **Every change updates the README.** Any change to how the queue is run, configured, or what topics exist updates `queue/README.md` in the same change set.

## Local run

See `README.md`. In short: `../dev.sh up` (from repo root) starts Kafka and creates topics; or `docker compose up -d` here, then `./create-topics.sh`, for queue-only iteration.
