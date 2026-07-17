# Cloak — Server

Spring Boot 4 backend. Authenticates requests by validating Keycloak-issued OIDC/OAuth 2.0 JWTs (it delegates login and credential management to Keycloak), manages WebSocket connections for real-time messaging, and routes encrypted message payloads between clients. Publishes delivery events to Kafka and persists encrypted history to PostgreSQL. The server never decrypts message content.

## Prerequisites

- Java 25+
- PostgreSQL on `localhost:5432`, Kafka on `localhost:9092`, Schema Registry on `localhost:8085`, and Keycloak on `localhost:8081` — start them all (and create the Kafka topics) with `./dev.sh up` from the repo root
- Keycloak supplies the OIDC issuer the server validates tokens against
- **Slice 2 — user lookup:** a `cloak-server-admin` confidential service-account client in Keycloak with the `view-users` role is required for `GET /v1/users/lookup`. See `../iam/README.md` for setup and `application.yml` for the `cloak.keycloak-admin.*` bindings.

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

## Observability

The server exports **metrics, traces, and logs over OTLP** to a local `grafana/otel-lgtm` stack
(Collector + Prometheus + Loki + Tempo + Grafana), started by `./dev.sh up` as the `obs/` component.

- **View it:** Grafana at `http://localhost:3000` (anonymous admin, local dev only). The provisioned
  **"Cloak server — overview"** dashboard shows HTTP rate/latency/errors, messages-routed rate, Kafka
  consumer lag, Hikari pool, and JVM stats.
- **Follow one request:** every response carries an `X-Trace-Id` header (and `traceId` in the body)
  that **is the OTel trace id** — paste it into Grafana → Explore → Tempo to see the full
  WS/HTTP → JDBC → Kafka trace, and the correlated Loki logs (filter `| trace_id="<id>"`).
- **Privacy:** no ciphertext, message body, or PII is ever exported; `sub` is never a metric label
  (asserted by `TelemetryPrivacyIntegrationTest`).
- **Fail-open:** the OTLP exporters are non-blocking — if the stack is down the server keeps serving.

Endpoints are overridable per environment via `OTLP_HTTP_URL`; in production the same OTLP target is a
standalone OpenTelemetry Collector (no app change). See `ARCHITECTURE_GUIDE.md` §10.5 and `obs/README.md`.

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

### `PUT /v1/keys`

Publishes (or replaces) the calling device's public Signal Protocol prekey bundle. The server
records public key material only — private keys never leave the device. Idempotent: a second PUT
replaces the existing bundle for the same `(owner, deviceId)` pair.

```
Authorization: Bearer <Keycloak access token>
Content-Type: application/json

{
  "registrationId": 12345,
  "deviceId": 1,
  "identityKey": "<base64, 33 bytes>",
  "signedPreKey": { "keyId": 1, "publicKey": "<base64, 33 bytes>", "signature": "<base64, 64 bytes>" },
  "oneTimePreKeys": [ { "keyId": 1, "publicKey": "<base64, 33 bytes>" } ]
}

→ 204 No Content     on success
→ 400 Bad Request    when key bytes have wrong length, duplicate one-time prekey ids, or missing fields
→ 401 Unauthorized   when the token is missing, expired, or fails audience validation
```

The owner `sub` is taken from the validated JWT — the body never carries the caller's identity.

### `GET /v1/keys/{sub}`

Fetches a recipient's public prekey bundle for X3DH key agreement (Slice 2). Atomically
consumes exactly one one-time prekey from the recipient's pool (sets `consumed_at`; if no
OTP is available the bundle is returned without one). Flyway **V3** adds the `kyber_prekey`
table for ML-KEM-1024 (PQXDH) material; `PUT /v1/keys` stores a last-resort Kyber key
alongside the EC keys.

```
Authorization: Bearer <Keycloak access token>
→ 200 { "data": { "registrationId": …, "deviceId": 1, "identityKey": "<b64>",
                  "signedPreKey": { "keyId": …, "publicKey": "<b64>", "signature": "<b64>" },
                  "oneTimePreKey": { "keyId": …, "publicKey": "<b64>" } | null,
                  "kyberPreKey": { "keyId": …, "publicKey": "<b64>", "signature": "<b64>" } },
        "errors": null, "traceId": "<id>" }
→ 404 Not Found    when no device is registered for {sub}
→ 401 Unauthorized when the token is missing, expired, or fails audience validation
```

### `GET /v1/users/lookup?handle=`

Resolves an exact email address or username to the recipient's Keycloak `sub` and device
number. Exact-match only — no prefix search, no listing (privacy: root CLAUDE.md §0.6).
Uses the `cloak-server-admin` service account to call the Keycloak Admin REST API.

```
Authorization: Bearer <Keycloak access token>
→ 200 { "data": { "sub": "<uuid>", "deviceId": 1 }, "errors": null, "traceId": "<id>" }
→ 404 Not Found          when no exact match exists
→ 401 Unauthorized       when the token is missing, expired, or fails audience validation
→ 503 Service Unavailable (code DEPENDENCY_UNAVAILABLE) when the Keycloak Admin API is unreachable —
      retries on 5xx / timeouts / 429 are exhausted, or the circuit breaker is open
→ 502 Bad Gateway        (code UPSTREAM_REJECTED) when the Keycloak Admin API returns a definitive 4xx
      (e.g. the service-account credentials are misconfigured)
```

Outbound calls to the Keycloak Admin API carry idempotent retries and a circuit breaker
(Resilience4j `@Retry`/`@CircuitBreaker`, tuned under `resilience4j.*` in `application.yml`,
ARCHITECTURE_GUIDE §7.4). Transient failures (5xx, connect/read timeouts, 429) are retried and, if
they persist or the breaker opens, surface as **503**; a definitive 4xx surfaces as **502**. The
upstream response body is never logged or returned (privacy: root CLAUDE.md §0.6).

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
