# Cloak

A privacy-first, end-to-end encrypted messaging app. Messages are encrypted on-device and the server never holds plaintext. AI features run locally via an on-device model (Gemma 3N), so no user data is sent to a hosted LLM.

## Components

| Folder | Description |
|--------|-------------|
| `app/` | iOS client (Swift / SwiftUI) |
| `server/` | Backend API and WebSocket hub (Java 25, Spring Boot 4) |
| `iam/` | Keycloak realm configuration — identity & access management |
| `db/` | PostgreSQL schema and migrations (Flyway) |
| `queue/` | Kafka topic configuration |
| `obs/` | Local observability stack (`grafana/otel-lgtm`: Collector + Prometheus + Loki + Tempo + Grafana) |

## Architecture

- **Transport:** WebSockets, with automatic fallback to long-polling
- **Encryption:** End-to-end — encrypt/decrypt on device only
- **Identity:** Keycloak — user accounts and authentication via OpenID Connect (OAuth 2.0); the server validates JWTs and never handles passwords
- **Storage:** PostgreSQL — encrypted message history and public key registry
- **Delivery:** Kafka — async fan-out to offline and multi-device recipients
- **Observability:** App-level metrics, traces, and logs exported over OTLP to an OpenTelemetry Collector seam (locally `grafana/otel-lgtm`); never any ciphertext or PII in a signal
- **AI:** On-device inference (Gemma 3N or similar) — no message content leaves the device

## Running locally

You'll need PostgreSQL, Kafka, and Keycloak running before starting the server. Each lives in its own folder with its own `docker-compose.yml`; the root `dev.sh` script brings them all up together (and creates the Kafka topics). Then start the server in its own terminal.

```bash
# Start all infra (Postgres, Kafka, Keycloak, observability) and create Kafka topics
./dev.sh up

# Server
cd server && ./gradlew bootRun
```

Use `./dev.sh down` to stop everything, or run `docker compose up -d` inside an individual folder (`db/`, `queue/`, `iam/`, `obs/`) to iterate on one component in isolation. Telemetry is viewable in Grafana at `http://localhost:3000` once the server is running (see `server/README.md` → Observability).

The iOS app is run via Xcode — open `app/` once the project is initialised.

## Prerequisites

- Java 25+
- Docker (for PostgreSQL and Kafka in local development)
- Xcode 16+ with an iOS 17+ simulator or device
