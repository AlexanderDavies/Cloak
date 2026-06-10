# Cloak — Server

Spring Boot 4 backend. Authenticates requests by validating Keycloak-issued OIDC/OAuth 2.0 JWTs (it delegates login and credential management to Keycloak), manages WebSocket connections for real-time messaging, and routes encrypted message payloads between clients. Publishes delivery events to Kafka and persists encrypted history to PostgreSQL. The server never decrypts message content.

## Prerequisites

- Java 25+
- PostgreSQL on `localhost:5432`, Kafka on `localhost:9092`, Schema Registry on `localhost:8085`, and Keycloak on `localhost:8081` — start them all (and create the Kafka topics) with `./dev.sh up` from the repo root
- Keycloak supplies the OIDC issuer the server validates tokens against

## Run locally

```bash
./gradlew bootRun
```

Server starts on `http://localhost:8080` by default.

## Test

```bash
./gradlew test              # fast unit + ArchUnit boundary tests
./gradlew integrationTest   # @SpringBootTest on Testcontainers (Docker required)
./gradlew check             # everything + Checkstyle (Google) + JaCoCo ≥90% gate
```

Integration tests start real Postgres, Kafka, and Keycloak containers and apply
the Flyway migrations from `../db/migrations`. Quality gates (Checkstyle, JaCoCo
≥90%) fail the build on violation — see root `CLAUDE.md`.

## Configuration

Edit `src/main/resources/application.yml` for local overrides (port, database URL, Kafka broker address, etc.).

## API

### Response envelope

Every REST response — success or failure — is a `WrappedResponse`:
`{ "data": …, "errors": …, "traceId": … }` (ARCHITECTURE_GUIDE §9). On success `data`
carries the resource and `errors` is `null`; on failure `data` is `null` and `errors` is a
non-empty array of `{ code, message, field? }`, one element per distinct problem (bean
validation yields one per field). `traceId` is on every body and also returned as the
`X-Trace-Id` response header (set by `CorrelationFilter`, which echoes an inbound `X-Trace-Id`
or generates one). Error `message`s are sanitised, stable text — never stack traces, SQL,
ciphertext, or PII. Auth failures (401/403) emit the same envelope from the Spring Security
entry point / access-denied handler.

### `GET /v1/me`

Returns the authenticated caller's Keycloak `sub`.

```
Authorization: Bearer <Keycloak access token>
→ 200 { "data": { "sub": "<sub>" }, "errors": null, "traceId": "<id>" }
→ 401 { "data": null, "errors": [ { "code": "UNAUTHORIZED", "message": "Authentication required" } ], "traceId": "<id>" }
     when the token is missing, expired, or fails audience validation
```

### `/ws` — WebSocket

Requires `Authorization: Bearer <Keycloak access token>` at the HTTP upgrade handshake.
Unauthenticated upgrade attempts are rejected with HTTP 401.

**Inbound frame (client → server):** matches `docs/contracts/phase0-message-envelope.md`. The
client sends `{ messageId, toSub, deviceId, ciphertext(base64) }` — there is no `fromSub` in the
inbound frame; the server derives the sender identity from the authenticated JWT `sub`.

On receipt, the server persists the ciphertext byte-for-byte to PostgreSQL, then publishes an
Avro envelope to `cloak.messages.outbound` keyed by the recipient's `sub`.

**Delivery frame (server → recipient):** the consumer reads the Avro envelope and forwards
`{ messageId, toSub, fromSub, deviceId, ciphertext(base64) }` to every open WebSocket session
of the recipient. `fromSub` is the server-stamped sender `sub` (not client-supplied).

**Auth model:** JWKS-validated Keycloak tokens (issuer `http://localhost:8081/realms/cloak`,
audience `cloak-api`). Local-dev seed users `alice` / `bob` (password `password`) are available
via the seeded realm (`iam/realm/cloak-realm.json`).

## Local smoke test

With the infra (`./dev.sh up`, from repo root) and the server (`./gradlew bootRun`) running,
[`scripts/smoke.sh`](scripts/smoke.sh) drives the local flow using the seeded `cloak-test`
client (users `alice`/`bob`, password `password`):

```bash
./scripts/smoke.sh health            # GET /actuator/health
./scripts/smoke.sh me alice          # /v1/me with no token (401) then a valid one (200 + sub)
./scripts/smoke.sh token alice       # print a raw access token (for Postman / curl)
./scripts/smoke.sh roundtrip "hi"    # full alice -> bob WebSocket round-trip
```

Deps: `curl`, `jq`, `python3`; `roundtrip` also needs [`websocat`](https://github.com/vi/websocat)
(`brew install websocat`). For Postman: GET `http://localhost:8080/v1/me` with a Bearer token from
`token`, or open a WebSocket request to `ws://localhost:8080/ws` with an `Authorization: Bearer`
header.

Two behaviours are intentional in this Phase 0 skeleton: the **recipient must be connected when the
sender sends** (the session registry is in-memory — no offline replay yet; the message is still
persisted and published, just not delivered live), and **`deviceId` must be `null`** (a non-null
value hits the `device_id → device(id)` FK, and no devices are registered yet).

## Architecture

The engineering guide (Hexagonal + DDD + BDD under mandatory TDD) lives in [`docs/ARCHITECTURE_GUIDE.md`](docs/ARCHITECTURE_GUIDE.md). `CLAUDE.md` carries a summary and the every-cycle operating principles; read the full guide before feature work.
