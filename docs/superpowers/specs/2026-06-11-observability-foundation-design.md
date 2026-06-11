# Observability Foundation — Design

**Status:** Approved design (brainstormed 2026-06-11). Next: `writing-plans`.

**Goal.** Stand up the server's three-signal observability — **metrics + logs + distributed traces** —
emitted **app-level**, pushed via **OTLP** to an **OpenTelemetry Collector seam**, and visualised
locally in **Grafana**. Vendor-neutral by construction: going cloud-native later is a Collector
re-point, not an app change. This is the concrete backend for `server/docs/ARCHITECTURE_GUIDE.md` §10
(which already prescribes the app-side abstractions but leaves the backend open). Cross-cutting
"Phase 0.5", **server-only**, privacy-first.

**Why now.** The walking skeleton is the cheapest surface to establish the §10 patterns (correlation,
structured logs, the WS→use-case→DB→Kafka trace, use-case latency). Retrofitting after the feature
slices accumulate is far more expensive — every later slice should *inherit* instrumentation, exactly
as it inherits the quality gates.

---

## Architecture

### Local topology (single container)
A new `obs/` component (its own `docker-compose.yml`, wired into `dev.sh` alongside `db`/`queue`/`iam`)
runs **`grafana/otel-lgtm`** — one image bundling an **OTel Collector** (OTLP receiver), **Prometheus**
(metrics), **Loki** (logs), **Tempo** (traces), and **Grafana** (UI, pre-wired to all three). The
server exports OTLP (gRPC `:4317`) to it; Grafana on `:3000`.

### The swap seam
The app **always** exports OTLP to a single configured endpoint. Locally that endpoint is
`otel-lgtm`'s embedded Collector. In a future non-local deployment it is a **standalone OTel Collector**
that fans out to the chosen cloud backend (CloudWatch/AMP/X-Ray, GCP Cloud Operations, Datadog, Grafana
Cloud, …). The app never knows the backend — swapping it is Collector exporter config + an endpoint
change, zero app code. (The standalone prod Collector is **deferred** — see below.)

### Transport
**OTLP-push for all three signals** (decided). Logs and traces are push-only anyway; pushing metrics
over OTLP too keeps one protocol, one export path, one seam. (Prometheus-scrape was considered and
rejected for non-uniformity.)

---

## What is emitted (app-level)

### Metrics — Micrometer → OTLP
- **Auto:** HTTP server (request latency/count by route + status), **HikariCP** pool (active/idle/
  pending, acquire time), **Spring Kafka** producer/consumer client metrics (throughput, **consumer
  lag**, errors), JVM + virtual-thread basics.
- **Custom (§10.4):** a small set of business metrics — a message-routed **counter** and a route
  **latency timer/histogram** at the use-case boundary, broken down by outcome. No high-cardinality or
  sensitive labels (see Privacy).

### Traces — Micrometer Tracing + OTel bridge → OTLP
- **Auto spans:** HTTP, **JDBC** (each query a span, so DB time appears inside the request trace),
  and **Kafka send/receive** with W3C context propagation in record headers — so a **single trace
  follows a message end-to-end**: WS ingest → `RouteMessageUseCase` → DB persist → Kafka publish →
  consumer → WS delivery, with timing per hop.
- **Manual span:** the WebSocket ingest path is wrapped explicitly (WebSocket is not auto-instrumented).

### Logs — Boot 4 structured logging → Loki (via OTLP)
- Structured **JSON** log lines, each carrying `traceId`/`spanId` (+ the §10 MDC fields as they land),
  shipped to Loki. Grafana links **logs ↔ traces** by `traceId`.

---

## Correlation: one id end-to-end
The existing minimal `CorrelationFilter` (Phase 0 server-skeleton) currently generates its **own** UUID
`traceId`. It will be **reconciled to use OTel's trace id**: the MDC `traceId` (and therefore the API
envelope's `traceId` field and `X-Trace-Id` response header) becomes the **OTel trace id**. Result: a
client-surfaced error reference resolves directly to the distributed trace in Grafana — one id from the
client error, through the logs, to the trace. The filter keeps owning the response header + envelope
field; it sources the value from the active trace context instead of minting a UUID.

---

## Privacy guardrails (hard rules)
Telemetry is subject to the same E2EE invariants as everything else (root `CLAUDE.md` §0.6, §10):
- **Never** log, trace-attribute, or metric-label **ciphertext, message bodies, tokens, or PII**.
- **`sub`** may appear as a **trace/log attribute** (correlation) but **never as a metric label**
  (unbounded cardinality + privacy).
- Telemetry config carries only routing/delivery metadata, consistent with principle 6.
- **A test asserts** no ciphertext/body value appears in any emitted signal (metric, trace, or log).

---

## Resilience
Telemetry export is **non-blocking and fail-open**: if the Collector/`otel-lgtm` is down, the app keeps
serving. The OTLP exporters buffer and drop on overflow; **nothing on the request/message hot path
blocks on telemetry**. Exporter failures are logged, not propagated.

---

## Testing (TDD)
- Inner loop on **Testcontainers `grafana/otel-lgtm`** (the OTel-LGTM stack container). An integration
  test exercises a representative flow (an authenticated request and/or the message round-trip) and
  asserts, by querying the embedded Prometheus / Tempo / Loki HTTP APIs:
  1. a **metric** was exported (e.g. the route-latency timer / HTTP server metric),
  2. a **trace** exists with the expected spans (HTTP/WS → JDBC → Kafka),
  3. a **log** line carries the request's `traceId`,
  4. **privacy:** no ciphertext / message-body value appears in any of the three signals.
- Coverage stays ≥90% on meaningful code; telemetry wiring lives in `common/config` (coverage-excluded)
  with the behaviour-bearing pieces (e.g. the correlation reconciliation, custom metrics) tested.

---

## Scope boundaries (YAGNI)
**In:** app-level metrics/logs/traces over OTLP; the `obs/` `otel-lgtm` stack + `dev.sh` wiring; the
correlation reconciliation; a couple of custom business metrics; **one starter Grafana dashboard**
(service overview: request rate/latency/errors, JVM, Kafka lag, DB pool); the privacy test.

**Deferred (noted, not built here):**
- **Infra-level telemetry** — scraping the **Kafka broker** (JMX / broker-side consumer-group lag) and
  **Postgres** (`pg_stat_*`: connections, cache-hit, locks, slow queries) directly via OTel Collector
  receivers. *(Explicitly requested as a fast-follow.)*
- **Curated dashboard suite + SLOs/alerting** (beyond the one starter dashboard).
- **Standalone production Collector** config + cloud-backend exporters (the seam is designed for it).
- **iOS-side observability** (separate, client-scoped effort).

---

## Definition of done (this slice)
- `./dev.sh up` starts `otel-lgtm`; the server exports metrics, logs, and traces to it over OTLP.
- Grafana shows the service's metrics, logs, and traces, with **logs↔traces correlation by `traceId`**.
- The API envelope `traceId` / `X-Trace-Id` equals the OTel trace id (end-to-end correlation).
- A request and the message round-trip produce a connected trace spanning HTTP/WS → JDBC → Kafka.
- The privacy test passes (no ciphertext/body/PII in any signal); `./gradlew check` green, ≥90%.
- One starter Grafana dashboard is provisioned; `server/README.md` + `ARCHITECTURE_GUIDE` §10 document
  the backend and how to view telemetry locally.

## Tech notes (to confirm in `writing-plans`, not binding here)
Likely dependencies/mechanisms: `micrometer-registry-otlp` (metrics), `micrometer-tracing-bridge-otel`
+ `opentelemetry-exporter-otlp` (traces), Spring Kafka observation (`observationEnabled`) for Kafka
spans, a datasource/JDBC observation lib for query spans, Boot structured logging + OTLP log export.
Boot 4 / Micrometer / OTel exact coordinates and property names are pinned during plan-writing.
