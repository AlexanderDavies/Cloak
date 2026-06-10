# Cloak — Queue (Kafka)

Single-node Apache Kafka (KRaft mode) + Confluent Schema Registry, run as Docker containers. Carries encrypted message payloads for reliable async delivery and fan-out to offline / multi-device recipients. Record values are **Avro**; ciphertext only — the queue never holds plaintext.

## Prerequisites

- Docker

## Run locally

From the repo root (starts the infra stack and creates topics):

```bash
./dev.sh up
```

Or queue-only, from this folder:

```bash
docker compose up -d   # starts Kafka (localhost:9092) + Schema Registry (localhost:8085)
./create-topics.sh     # idempotently creates the Cloak topics
docker compose down     # stop
```

Record values are Avro; schemas are managed by the Schema Registry at `http://localhost:8085`. See [`CLAUDE.md`](CLAUDE.md) → Serialization.

## Topics

Defined in [`create-topics.sh`](create-topics.sh) (the single source of truth), keyed by recipient userId for multi-device fan-out:

| Topic | Purpose |
|-------|---------|
| `cloak.messages.outbound` | Encrypted payloads delivered to recipients |
| `cloak.messages.receipts` | Delivery / read receipts back to senders |
| `cloak.messages.dlq` | Dead-letter for exhausted-retry payloads |

## Architecture & conventions

See [`CLAUDE.md`](CLAUDE.md) for keying/ordering, delivery semantics (at-least-once, idempotent consumers), and privacy guardrails.
