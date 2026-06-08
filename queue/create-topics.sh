#!/usr/bin/env bash
# Single source of truth for Cloak's Kafka topics. Idempotent — safe to re-run.
# Topics are keyed by recipient userId (Keycloak `sub`) for fan-out to a user's
# offline / multi-device endpoints. Runs commands inside the broker container so
# no host-side Kafka CLI is required.
set -euo pipefail

CONTAINER="${KAFKA_CONTAINER:-cloak-kafka}"
BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
PARTITIONS="${KAFKA_PARTITIONS:-12}"
RF="${KAFKA_REPLICATION_FACTOR:-1}"

topics_cli() {
  docker exec "$CONTAINER" /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" "$@"
}

# Wait for the broker to accept requests.
echo "waiting for kafka ($CONTAINER)..."
ready=
for _ in $(seq 1 30); do
  if topics_cli --list >/dev/null 2>&1; then ready=1; break; fi
  sleep 2
done
[ "$ready" = 1 ] || { echo "kafka not ready" >&2; exit 1; }

create() {
  local topic="$1" partitions="$2"
  topics_cli --create --if-not-exists --topic "$topic" \
    --partitions "$partitions" --replication-factor "$RF"
  echo "  ok: $topic ($partitions partitions, rf $RF)"
}

# Primary delivery stream: encrypted payloads fanned out to recipients.
create cloak.messages.outbound "$PARTITIONS"
# Delivery / read receipts flowing back toward senders.
create cloak.messages.receipts "$PARTITIONS"
# Dead-letter for payloads that exhausted delivery retries.
create cloak.messages.dlq 1

echo "topics:"
topics_cli --list | sed 's/^/  /'
