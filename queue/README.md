# Cloak — Queue (Kafka)

Single-node Apache Kafka (KRaft mode), run as a Docker container. Carries encrypted message payloads for reliable async delivery and fan-out to offline / multi-device recipients. Ciphertext only — the queue never holds plaintext.

## Prerequisites

- Docker

## Run locally

From the repo root (starts the infra stack and creates topics):

```bash
./dev.sh up
```

Or queue-only, from this folder:

```bash
docker compose up -d   # starts Kafka on localhost:9092
./create-topics.sh     # idempotently creates the Cloak topics
docker compose down     # stop
```

## Topics

Defined in [`create-topics.sh`](create-topics.sh) (the single source of truth), keyed by recipient userId for multi-device fan-out:

| Topic | Purpose |
|-------|---------|
| `cloak.messages.outbound` | Encrypted payloads delivered to recipients |
| `cloak.messages.receipts` | Delivery / read receipts back to senders |
| `cloak.messages.dlq` | Dead-letter for exhausted-retry payloads |

## Architecture & conventions

See [`CLAUDE.md`](CLAUDE.md) for keying/ordering, delivery semantics (at-least-once, idempotent consumers), and privacy guardrails.
