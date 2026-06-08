#!/usr/bin/env bash
# Cloak local infrastructure orchestrator.
# Brings the per-component Docker stacks (db, queue, iam) up/down together.
# Each component owns its own docker-compose.yml; the server (run via Gradle)
# reaches them over published localhost ports, so no shared network is needed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPONENTS=(db queue iam)

usage() { echo "usage: $0 {up|down|ps}"; exit 1; }

cmd="${1:-}"
[ -n "$cmd" ] || usage

for c in "${COMPONENTS[@]}"; do
  compose="$ROOT/$c/docker-compose.yml"
  if [ ! -f "$compose" ]; then
    echo "skip $c (no docker-compose.yml yet)"
    continue
  fi
  case "$cmd" in
    up)   echo "==> $c up";   docker compose -f "$compose" up -d ;;
    down) echo "==> $c down"; docker compose -f "$compose" down ;;
    ps)   echo "==> $c";      docker compose -f "$compose" ps ;;
    *) usage ;;
  esac
done

# After infra is up, ensure Kafka topics exist.
if [ "$cmd" = "up" ] && [ -x "$ROOT/queue/create-topics.sh" ]; then
  echo "==> creating Kafka topics"
  "$ROOT/queue/create-topics.sh"
fi
