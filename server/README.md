# Cloak — Server

Spring Boot 4 backend. Authenticates requests by validating Keycloak-issued OIDC/OAuth 2.0 JWTs (it delegates login and credential management to Keycloak), manages WebSocket connections for real-time messaging, and routes encrypted message payloads between clients. Publishes delivery events to Kafka and persists encrypted history to PostgreSQL. The server never decrypts message content.

## Prerequisites

- Java 25+
- PostgreSQL on `localhost:5432`, Kafka on `localhost:9092`, and Keycloak on `localhost:8081` — start them all with `./dev.sh up` from the repo root
- Keycloak supplies the OIDC issuer the server validates tokens against

## Run locally

```bash
./gradlew bootRun
```

Server starts on `http://localhost:8080` by default.

## Test

```bash
./gradlew test
```

## Configuration

Edit `src/main/resources/application.properties` for local overrides (port, database URL, Kafka broker address, etc.).

## Architecture

The engineering guide (Hexagonal + DDD + BDD under mandatory TDD) lives in [`docs/ARCHITECTURE_GUIDE.md`](docs/ARCHITECTURE_GUIDE.md). `CLAUDE.md` carries a summary and the every-cycle operating principles; read the full guide before feature work.
