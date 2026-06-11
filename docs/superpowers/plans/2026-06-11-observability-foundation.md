# Observability Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Emit the server's metrics, logs, and distributed traces app-level over OTLP to a local
`grafana/otel-lgtm` stack (an OTel Collector + Prometheus + Loki + Tempo + Grafana), so a single
message's path is traceable end-to-end and a client error reference resolves to its Grafana trace —
with the Collector as the vendor-swap seam.

**Architecture:** Spring Boot 4 / Micrometer + Micrometer Tracing (OTel bridge) export OTLP to a
single configured endpoint. Locally that endpoint is `grafana/otel-lgtm` (run as a new `obs/`
docker-compose component, wired into `dev.sh`); in production it would be a standalone OTel Collector.
Implements `server/docs/ARCHITECTURE_GUIDE.md` §10. Design: `docs/superpowers/specs/2026-06-11-observability-foundation-design.md`.

**Tech Stack:** Spring Boot 4.0.6, Micrometer (`micrometer-registry-otlp`), Micrometer Tracing
(`micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`), Spring Kafka observation,
`datasource-micrometer-spring-boot` (JDBC spans), Boot structured logging + OTLP log export,
Testcontainers `grafana/otel-lgtm` (`org.testcontainers:grafana` `LgtmStackContainer`), Awaitility.

**Workflow:** Branch `feature/phase-0.5-observability` off `main`. Run `./gradlew spotlessApply` before
every commit (Checkstyle/Spotless gate). Follow root `CLAUDE.md` quality gates (≥90% coverage, both
reviews before merge). Docker required for integration tests.

> **Boot 4 confirmation note (whole plan):** OTel/Micrometer property names and artifact versions move
> fast. Where a step gives a property or coordinate, **confirm it resolves on this Boot 4.0.6 toolchain**
> (`./gradlew dependencies`, and at runtime `GET /actuator/configprops` / `/actuator/metrics`). If a
> name differs in this version, adapt to the equivalent and record the change in the plan (living-plan
> discipline). These confirmations are real verification steps, not placeholders.

---

## File Structure

- `obs/docker-compose.yml` — new: `grafana/otel-lgtm` stack (OTLP 4317/4318, Grafana 3000).
- `obs/dashboards/cloak-server-overview.json` — new: one starter Grafana dashboard.
- `obs/README.md` — new: what the stack is, how to view telemetry locally.
- `dev.sh` — modify: add `obs` to `COMPONENTS`.
- `server/build.gradle` — modify: add OTLP metrics/tracing exporters, Kafka/JDBC observation, AOP.
- `server/src/main/resources/application.yml` — modify: OTLP endpoints, sampling, observation toggles.
- `server/src/main/java/com/cloak/server/common/config/ObservabilityConfig.java` — new: `ObservedAspect`
  bean + a custom `MeterRegistry`/tag wiring if needed (coverage-excluded `config` package).
- `server/src/main/java/com/cloak/server/usecase/RouteMessageUseCase.java` — modify: `@Observed` +
  message-routed counter.
- `server/src/main/java/com/cloak/server/adapter/input/websocket/MessageWebSocketHandler.java` — modify:
  wrap ingest in a manual Observation/span.
- `server/src/main/java/com/cloak/server/common/web/CorrelationFilter.java` — modify: source `traceId`
  from the active OTel trace context instead of a generated UUID.
- `server/src/integrationTest/java/com/cloak/server/support/IntegrationTestBase.java` — modify: add the
  `LgtmStackContainer`; register OTLP endpoint dynamic properties.
- `server/src/integrationTest/java/com/cloak/server/support/Telemetry.java` — new: helpers to query the
  stack's Prometheus/Tempo/Loki HTTP APIs.
- `server/src/integrationTest/java/com/cloak/server/observability/*IntegrationTest.java` — new: metrics,
  tracing, correlation, logging, and privacy assertions.
- `server/README.md`, `server/docs/ARCHITECTURE_GUIDE.md` §10 — modify: document the backend + how to view.

---

### Task 1: `obs/` local stack + `dev.sh` wiring

**Files:**
- Create: `obs/docker-compose.yml`, `obs/README.md`
- Modify: `dev.sh`

- [ ] **Step 1: Create `obs/docker-compose.yml`**

```yaml
name: cloak-obs

services:
  # All-in-one OTel Collector + Prometheus + Loki + Tempo + Grafana for local telemetry.
  # The server exports OTLP here; Grafana visualises metrics, logs, and traces.
  otel-lgtm:
    image: grafana/otel-lgtm:0.8.1
    container_name: cloak-otel-lgtm
    ports:
      - "4317:4317"   # OTLP gRPC
      - "4318:4318"   # OTLP HTTP
      - "3000:3000"   # Grafana UI
    environment:
      # Grafana anonymous admin for local dev only.
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: "Admin"
```
> Confirm the latest `grafana/otel-lgtm` tag (`docker pull grafana/otel-lgtm` / Docker Hub); pin it
> explicitly (don't use `latest`).

- [ ] **Step 2: Wire into `dev.sh`**

In `dev.sh`, add `obs` to the `COMPONENTS` array (so `./dev.sh up|down|ps` includes it):
```bash
COMPONENTS=(db queue iam obs)
```

- [ ] **Step 3: Create `obs/README.md`**

A short doc: what `otel-lgtm` is (single-container OTel Collector + Prometheus + Loki + Tempo +
Grafana), that the server exports OTLP to `localhost:4317`, and that Grafana is at
`http://localhost:3000` (anonymous admin, local-dev only). No marketing copy.

- [ ] **Step 4: Verify it starts**

Run (repo root): `./dev.sh up && curl -sf -o /dev/null -w '%{http_code}\n' http://localhost:3000/api/health`
Expected: `cloud-otel-lgtm` container up; Grafana health returns `200`. (`docker ps` shows `cloak-otel-lgtm`.)

- [ ] **Step 5: Commit**

```bash
git add obs dev.sh
git commit -m "$(printf 'feat(obs): local grafana/otel-lgtm stack wired into dev.sh\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 2: Testcontainers LGTM harness + OTLP exporter dependencies

**Files:**
- Modify: `server/build.gradle`, `server/src/main/resources/application.yml`
- Modify: `server/src/integrationTest/java/com/cloak/server/support/IntegrationTestBase.java`
- Create: `server/src/integrationTest/java/com/cloak/server/support/Telemetry.java`

- [ ] **Step 1: Add dependencies**

In `server/build.gradle` `dependencies {}`:
```groovy
implementation 'io.micrometer:micrometer-registry-otlp'
implementation 'io.micrometer:micrometer-tracing-bridge-otel'
implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
implementation 'org.springframework.boot:spring-boot-opentelemetry'   // Boot 4 OTel autoconfig module
implementation 'net.ttddyy.observation:datasource-micrometer-spring-boot:2.2.1'
implementation 'org.aspectj:aspectjweaver'   // for @Observed (ObservedAspect)

integrationTestImplementation 'org.testcontainers:grafana'
```
> Micrometer + OTel exporter versions are managed by the Spring Boot BOM (don't pin them). Confirm
> `datasource-micrometer-spring-boot` and `org.testcontainers:grafana` resolve (`./gradlew dependencies
> --configuration integrationTestRuntimeClasspath`); bump to the latest compatible if needed.
>
> **Deviations recorded at execution (2026-06-11):**
> - `grafana/otel-lgtm` pinned to **0.28.0** (latest; plan draft said 0.8.1) — in `obs/docker-compose.yml`
>   and `IntegrationTestBase`. The image ships `curl` (not `wget`), so the compose healthcheck uses curl.
> - `datasource-micrometer-spring-boot` bumped **1.0.5 → 2.2.1**: 1.x targets Boot 3 and its
>   `DataSourceObservationAutoConfiguration` references `o.s.b.actuate.autoconfigure.observation.
>   ObservationRegistryCustomizer`, which Boot 4 relocated to `o.s.b.micrometer.observation.
>   autoconfigure` → `NoClassDefFoundError` at context load. The 2.x line depends on the new Boot 4
>   modules (`spring-boot-micrometer-observation/-metrics/-tracing`, `spring-boot-jdbc`).
> - **Added `org.springframework.boot:spring-boot-opentelemetry`** (BOM-managed). Boot 4 extracted the
>   OpenTelemetry autoconfig (`OpenTelemetryProperties` + SDK beans) into this module, and **none of the
>   starters pull it**. Without it, `OtlpMetricsExportAutoConfiguration` (and the OTLP tracing autoconfig)
>   silently `@ConditionalOnClass`-skip on `o.s.b.opentelemetry.autoconfigure.OpenTelemetryProperties`, so
>   **nothing is exported** — metrics never reach Prometheus and the OTLP exporters stall. Diagnosed via
>   the `--debug` condition-evaluation report. This is the single most important dependency for the whole
>   plan.
> - **`spring-boot-starter-aop` was discontinued in Spring Boot 4** (GA ends at 3.5.x; only a 4.0.0
>   milestone exists, so the BOM-resolved 4.0.6 coordinate 404s). Replaced with BOM-managed
>   `org.aspectj:aspectjweaver`, which puts AspectJ on the classpath and triggers Boot's
>   `AopAutoConfiguration`; `spring-aop` arrives transitively via spring-context. `ObservedAspect` works
>   the same.
> - **Added `org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry`** (BOM-managed) —
>   the analogue of `spring-boot-opentelemetry` for *tracing*: it provides `OpenTelemetryTracingAuto-
>   Configuration` (the OTel `Tracer`) and `OtlpTracingAutoConfiguration` (the OTLP **span** exporter).
>   `spring-boot-opentelemetry` only wires the SDK + OTLP *logging*; without this module spans are
>   created but never exported (Tempo stays empty).
> - **Boot 4 OTLP property prefixes differ per signal** (confirmed against the autoconfig `@Configuration-
>   Properties`): metrics `management.otlp.metrics.export.url`; tracing
>   `management.opentelemetry.tracing.export.otlp.endpoint` (the plan's `management.otlp.tracing.endpoint`
>   is the Boot-3 name and is silently ignored); sampling stays `management.tracing.sampling.probability`.
> - The message publisher uses a **hand-built `KafkaTemplate`** bean, so `spring.kafka.template.
>   observation-enabled` (auto-template only) does not reach it — call `setObservationEnabled(true)` on the
>   bean so the producer span joins the trace. The consumer uses Boot's auto factory, so
>   `spring.kafka.listener.observation-enabled: true` is enough there.
> - **OTLP logging (Task 6):** export prefix is `management.opentelemetry.logging.export.otlp.endpoint`
>   (the plan's `management.otlp.logging.*` is wrong for Boot 4). Boot wires the SDK LoggerProvider +
>   OTLP log exporter but **installs no Logback→OTel bridge**, so SLF4J logs never ship. Added
>   `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.21.0-alpha` (alpha is its
>   only line; not BOM-managed) and attach it to the root logger **programmatically** in
>   `ObservabilityConfig` rather than via `logback-spring.xml` — a custom logback file silently
>   overrides Boot's structured-console (`logging.structured.format.console: ecs`), so doing it in code
>   keeps the ECS console. The appender captures the OTel trace context natively, so `trace_id`/`span_id`
>   land as Loki labels; the test asserts via `{service_name="cloak-server"} | trace_id="<id>"`.
>   **The appender version MUST track the `opentelemetry-api` the Boot BOM manages (1.55.0):** a newer
>   appender calls `LogRecordBuilder.setException(Throwable)` (added in api 1.56.0) and throws
>   `NoSuchMethodError` — an `Error`, so Logback's appender guard doesn't swallow it — on every
>   exception-bearing log. `2.21.0-alpha` targets api 1.55.0. Guarded by
>   `LoggingExceptionIntegrationTest` (the original `log.warn` test never carried a `Throwable`).
> - Set the OTel **resource** `service.name=cloak-server` via `management.opentelemetry.resource-attributes`
>   (the plan's `management.observations.key-values.service.name` only adds a metric tag / span attribute,
>   not the resource attribute that labels every signal in Prometheus/Tempo/Loki).
> - Resolved versions: `micrometer-registry-otlp` 1.16.5, `micrometer-tracing-bridge-otel` 1.6.5,
>   `opentelemetry-exporter-otlp` 1.55.0, `org.testcontainers:grafana` 1.21.4 (via the pinned TC BOM).

- [ ] **Step 2: Base OTLP config in `application.yml`**

Add (merge into the existing `management`/top-level tree; endpoints overridable per environment):
```yaml
management:
  otlp:
    metrics:
      export:
        url: ${OTLP_HTTP_URL:http://localhost:4318}/v1/metrics
        step: 5s
    tracing:
      endpoint: ${OTLP_HTTP_URL:http://localhost:4318}/v1/traces
  tracing:
    sampling:
      probability: 1.0          # sample everything in dev/test; tune per environment later
  observations:
    key-values:
      service.name: cloak-server
```
> `management.otlp.metrics.export.url` and `management.otlp.tracing.endpoint` are the Boot/Micrometer
> OTLP property names; confirm via `/actuator/configprops` once the app boots. Logs OTLP is added in
> Task 6.

- [ ] **Step 3: Add the LGTM container to `IntegrationTestBase`**

In `IntegrationTestBase` add, alongside the existing containers:
```java
static final org.testcontainers.grafana.LgtmStackContainer LGTM =
    new org.testcontainers.grafana.LgtmStackContainer("grafana/otel-lgtm:0.8.1");
```
Add `LGTM` to the `Startables.deepStart(...)` set. In `props(...)`, point the app's OTLP exporters at
the container (HTTP OTLP):
```java
r.add("management.otlp.metrics.export.url", () -> LGTM.getOtlpHttpUrl() + "/v1/metrics");
r.add("management.otlp.tracing.endpoint", () -> LGTM.getOtlpHttpUrl() + "/v1/traces");
r.add("management.otlp.metrics.export.step", () -> "1s"); // fast push so tests don't wait long
```
> Confirm the `LgtmStackContainer` accessor names (`getOtlpHttpUrl()`, `getGrafanaHttpUrl()` /
> `getHttpUrl()`) against the resolved `org.testcontainers:grafana` version's Javadoc; adapt if
> different. Expose a `grafanaUrl()` / `prometheusUrl()` / `tempoUrl()` / `lokiUrl()` helper as needed —
> with `otel-lgtm`, Prometheus/Tempo/Loki are reachable through the Grafana datasource proxy or their
> own ports; the `Telemetry` helper (next step) encapsulates the exact query URLs.

- [ ] **Step 4: Create the `Telemetry` query helper**

Create `support/Telemetry.java` — small helpers that query the stack and return whether a signal is
present. Use Grafana's datasource proxy or the embedded service APIs (confirm against `otel-lgtm`):
- `boolean metricExists(String promQl)` → Prometheus `GET /api/v1/query?query=<promQl>`, true if the
  result vector is non-empty.
- `boolean traceExists(String tempoQuery)` → Tempo `GET /api/search?q=<query>` (TraceQL), true if any
  trace matches.
- `String logsContaining(String lokiQuery)` → Loki `GET /loki/api/v1/query_range?query=<logQl>`, returns
  the matched log lines.
Wrap each in Awaitility-friendly boolean checks. Include a clear `IllegalStateException` on HTTP error.
> The exact base URLs for Prometheus/Tempo/Loki inside `otel-lgtm` must be confirmed (they may be the
> Grafana proxy `/api/datasources/proxy/...` or fixed internal ports). Pin them here once verified.

- [ ] **Step 5: Verify the harness compiles and the container starts**

Run: `./gradlew compileIntegrationTestJava` → BUILD SUCCESSFUL. (Full assertions land in later tasks.)

- [ ] **Step 6: Commit**

```bash
git add build.gradle src/main/resources src/integrationTest ../docs/superpowers/plans/2026-06-11-observability-foundation.md
git commit -m "$(printf 'feat(obs): OTLP exporter deps + Testcontainers LGTM harness\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 3: Metrics — custom use-case metric exported over OTLP

**Files:**
- Modify: `server/src/main/java/com/cloak/server/usecase/RouteMessageUseCase.java`
- Create: `server/src/main/java/com/cloak/server/common/config/ObservabilityConfig.java`
- Create: `server/src/integrationTest/java/com/cloak/server/observability/MetricsIntegrationTest.java`

- [ ] **Step 1: Write the failing integration test**

Create `observability/MetricsIntegrationTest.java` extending `IntegrationTestBase`. Drive the
`RouteMessageUseCase` (autowire it; route a message with `deviceId=null`), then `await()` up to ~15s
asserting `Telemetry.metricExists("cloak_messages_routed_total")` (or the route-latency timer
`cloak_message_route_seconds_count`). No `sub`/ciphertext labels.

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew integrationTest --tests '*MetricsIntegrationTest'` → FAIL (metric absent).

- [ ] **Step 3: Add the `ObservedAspect` bean**

Create `common/config/ObservabilityConfig.java`:
```java
package com.cloak.server.common.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfig {
  @Bean
  ObservedAspect observedAspect(ObservationRegistry registry) {
    return new ObservedAspect(registry);
  }
}
```

- [ ] **Step 4: Instrument the use case**

In `RouteMessageUseCase`, annotate `route(...)` with `@io.micrometer.observation.annotation.Observed(name = "cloak.message.route")`
(produces a timer metric + a span) and increment a routed counter via an injected `MeterRegistry`:
```java
// constructor: add MeterRegistry registry; field this.routed = registry.counter("cloak.messages.routed");
// in route(...), after publish: routed.increment();
```
Keep labels minimal — **no `sub`, no ciphertext**.
> If injecting `MeterRegistry` into the use case feels too infra-coupled, move the counter to a small
> `@Observed`-only approach or an adapter; the timer from `@Observed` alone satisfies §10.4 use-case
> latency. Decide and record.

- [ ] **Step 5: Run to confirm it passes**

Run: `./gradlew spotlessApply integrationTest --tests '*MetricsIntegrationTest'` → PASS (metric
exported and visible in Prometheus).

- [ ] **Step 6: Commit**

```bash
git add src/main src/integrationTest ../docs/superpowers/plans/2026-06-11-observability-foundation.md
git commit -m "$(printf 'feat(obs): use-case metrics over OTLP (routed counter + route timer)\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 4: Distributed tracing — end-to-end span over OTLP

**Files:**
- Modify: `server/src/main/resources/application.yml` (Kafka/JDBC observation toggles)
- Modify: `server/src/main/java/com/cloak/server/adapter/input/websocket/MessageWebSocketHandler.java`
- Create: `server/src/integrationTest/java/com/cloak/server/observability/TracingIntegrationTest.java`

- [ ] **Step 1: Write the failing test**

Create `TracingIntegrationTest.java` extending `IntegrationTestBase`. Perform the authenticated
**round-trip** (reuse the `Tokens`/WebSocket pattern from the server-skeleton `RoundTripIntegrationTest`):
alice sends, bob receives. Then `await()` asserting `Telemetry.traceExists(...)` for a trace whose
spans include the HTTP/WS ingest, a JDBC span, and a Kafka producer + consumer span (TraceQL by
service name + span name). Assert at least the WS-ingest span and a `kafka` span are present.

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew integrationTest --tests '*TracingIntegrationTest'` → FAIL (no trace / missing spans).

- [ ] **Step 3: Enable Kafka + JDBC observation**

In `application.yml` add:
```yaml
spring:
  kafka:
    template:
      observation-enabled: true
    listener:
      observation-enabled: true
```
JDBC spans come from `datasource-micrometer-spring-boot` (added in Task 2) automatically once tracing is
on. Confirm `management.tracing.sampling.probability: 1.0` (Task 2) so every trace is exported.

- [ ] **Step 4: Wrap WebSocket ingest in a manual span**

In `MessageWebSocketHandler.handleTextMessage`, wrap the parse+route in an `Observation` (inject
`ObservationRegistry`) named `cloak.ws.ingest` so the WS hop appears as a span and Kafka producer
context propagates from inside it:
```java
// Observation.createNotStarted("cloak.ws.ingest", observationRegistry).observe(() -> { parse + route; });
```
Never put ciphertext or message content on the observation. Do **not** add `sub` as a
**low-cardinality** key (low-cardinality keys become metric tags → unbounded cardinality + privacy); if
`sub` is attached for trace correlation at all, use a **high-cardinality** key only (trace attribute,
never a metric tag).

- [ ] **Step 5: Run to confirm it passes**

Run: `./gradlew spotlessApply integrationTest --tests '*TracingIntegrationTest'` → PASS (connected trace
with WS + Kafka spans in Tempo).

- [ ] **Step 6: Commit**

```bash
git add src/main src/integrationTest ../docs/superpowers/plans/2026-06-11-observability-foundation.md
git commit -m "$(printf 'feat(obs): end-to-end tracing over OTLP (WS, JDBC, Kafka spans)\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 5: Correlation — envelope `traceId` == OTel trace id

**Files:**
- Modify: `server/src/main/java/com/cloak/server/common/web/CorrelationFilter.java`
- Modify/Create: `server/src/test/java/com/cloak/server/common/web/CorrelationFilterTest.java`
- Create: `server/src/integrationTest/java/com/cloak/server/observability/CorrelationIntegrationTest.java`

- [ ] **Step 1: Write the failing integration test**

Create `CorrelationIntegrationTest.java`: call `GET /v1/me` with a token; capture the `X-Trace-Id`
response header (and the body `traceId`); `await()` asserting `Telemetry.traceExists` for a trace whose
**trace id equals that header value**. This proves the envelope id is the OTel trace id, not a separate
UUID.

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew integrationTest --tests '*CorrelationIntegrationTest'` → FAIL (envelope id is a UUID,
not the trace id; no Tempo match).

- [ ] **Step 3: Source `traceId` from the active trace context**

Refactor `CorrelationFilter`: inject Micrometer `Tracer`; inside `doFilterInternal`, after the request
enters the tracing context, set the MDC `traceId` + `X-Trace-Id` header from
`tracer.currentSpan().context().traceId()` when a span is active, falling back to the existing
generated UUID only when no span exists (e.g. a path that bypasses tracing). Preserve the
inbound-header validation from the skeleton (cap/charset) for the fallback path.
> Ordering matters: the trace context must be established before the filter reads it. Boot's tracing
> `ObservationFilter`/`TracingFilter` runs in the chain; confirm the `CorrelationFilter` reads a
> non-null `currentSpan()` for a normal request (it may need to run *after* the tracing filter rather
> than at `HIGHEST_PRECEDENCE`). If the order conflicts with the security-entry-point requirement
> (`HIGHEST_PRECEDENCE` so 401s carry the header), reconcile: e.g. keep generating an id early but
> overwrite both MDC and header with the trace id once the span is available, or align the trace id and
> the correlation id via the OTel context. Record the chosen approach.

- [ ] **Step 4: Update the unit test**

Adjust `CorrelationFilterTest` for the new behaviour (with a stubbed `Tracer` returning a known trace
id, assert the header/MDC use it; with no current span, assert the UUID fallback + inbound validation
still hold).

- [ ] **Step 5: Run to confirm both pass**

Run: `./gradlew spotlessApply test --tests '*CorrelationFilterTest'` then
`./gradlew integrationTest --tests '*CorrelationIntegrationTest'` → PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main src/test src/integrationTest ../docs/superpowers/plans/2026-06-11-observability-foundation.md
git commit -m "$(printf 'feat(obs): correlate envelope traceId with the OTel trace id\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 6: Structured logs → Loki with trace correlation

**Files:**
- Modify: `server/src/main/resources/application.yml`
- Create: `server/src/integrationTest/java/com/cloak/server/observability/LoggingIntegrationTest.java`

- [ ] **Step 1: Write the failing test**

Create `LoggingIntegrationTest.java`: trigger a request that logs (e.g. `/v1/me` or an error path),
capture its `X-Trace-Id`, then `await()` asserting `Telemetry.logsContaining(...)` returns a Loki log
line tagged/containing that `traceId`.

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew integrationTest --tests '*LoggingIntegrationTest'` → FAIL (no logs in Loki yet).

- [ ] **Step 3: Enable structured logging + OTLP log export**

In `application.yml`:
```yaml
logging:
  structured:
    format:
      console: ecs            # structured JSON to stdout (ECS); carries traceId/spanId
management:
  otlp:
    logging:
      endpoint: ${OTLP_HTTP_URL:http://localhost:4318}/v1/logs
      export:
        enabled: true
```
In `IntegrationTestBase.props(...)` add the dynamic logs endpoint:
`r.add("management.otlp.logging.endpoint", () -> LGTM.getOtlpHttpUrl() + "/v1/logs");`
> Confirm Boot 4's OTLP **log** export property names/support (`management.otlp.logging.*`) via
> `/actuator/configprops`. If OTLP log export is not first-class in this version, fall back to the
> OpenTelemetry Logback appender (`io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0`)
> configured to ship to the same OTLP endpoint. Record whichever path is used.

- [ ] **Step 4: Run to confirm it passes**

Run: `./gradlew spotlessApply integrationTest --tests '*LoggingIntegrationTest'` → PASS (log line with
the request's `traceId` is queryable in Loki).

- [ ] **Step 5: Commit**

```bash
git add src/main src/integrationTest ../docs/superpowers/plans/2026-06-11-observability-foundation.md
git commit -m "$(printf 'feat(obs): structured JSON logs to Loki with trace correlation\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 7: Privacy assertion — no ciphertext/body in any signal

**Files:**
- Create: `server/src/integrationTest/java/com/cloak/server/observability/TelemetryPrivacyIntegrationTest.java`

- [ ] **Step 1: Write the test**

Create `TelemetryPrivacyIntegrationTest.java`: perform the round-trip with a **recognisable ciphertext
marker** (e.g. bytes whose base64 is a unique sentinel like `"Q0lQSEVSVEVYVA=="`). After delivery,
assert that the sentinel does **not** appear in:
- any Loki log line (`Telemetry.logsContaining("CIPHERTEXT-sentinel")` is empty),
- any Tempo span attribute (search/inspect the trace's spans),
- any exported metric name/label.
Also assert the recipient `sub` is not present as a **metric label** (query Prometheus label values).

- [ ] **Step 2: Run it**

Run: `./gradlew integrationTest --tests '*TelemetryPrivacyIntegrationTest'` → PASS (no leak). If it
FAILS, a signal is leaking content — fix the offending instrumentation (remove the attribute/label);
do not weaken the test.

- [ ] **Step 3: Commit**

```bash
git add src/integrationTest ../docs/superpowers/plans/2026-06-11-observability-foundation.md
git commit -m "$(printf 'test(obs): assert no ciphertext/PII leaks into metrics, traces, or logs\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 8: Starter Grafana dashboard

**Files:**
- Create: `obs/dashboards/cloak-server-overview.json`
- Modify: `obs/docker-compose.yml` (provision the dashboard), `obs/README.md`

- [ ] **Step 1: Build the dashboard**

In Grafana (running locally), assemble a "Cloak server — overview" dashboard: HTTP request rate /
latency (p50/p95) / error rate, JVM (heap, threads), **Kafka consumer lag**, **Hikari pool** (active/
pending), and a link to traces. Export it as JSON to `obs/dashboards/cloak-server-overview.json`.

- [ ] **Step 2: Provision it**

Mount the dashboards dir into `otel-lgtm` so Grafana auto-loads it (confirm the image's provisioning
path, typically `/otel-lgtm/grafana/...` or a standard Grafana provisioning mount). Add the volume to
`obs/docker-compose.yml` and a note to `obs/README.md`.

- [ ] **Step 3: Verify**

Run: `./dev.sh down && ./dev.sh up`; confirm the dashboard appears in Grafana (`http://localhost:3000`)
and panels render once the server is running and traffic flows.

- [ ] **Step 4: Commit**

```bash
git add obs
git commit -m "$(printf 'feat(obs): starter Grafana service-overview dashboard\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 9: Full gate + docs

**Files:**
- Modify: `server/README.md`, `server/docs/ARCHITECTURE_GUIDE.md` (§10)

- [ ] **Step 1: Run the full gate**

Run: `./gradlew check` → BUILD SUCCESSFUL (Checkstyle, unit + ArchUnit, all `integrationTest` incl. the
new observability suites, JaCoCo ≥90%). The observability wiring lives in `common/config` (coverage-
excluded); the behaviour-bearing changes (correlation reconciliation, custom metrics) are tested.

- [ ] **Step 2: Verify fail-open (telemetry is non-blocking)**

With the app running locally (`./gradlew bootRun`) and infra up, stop only the telemetry backend
(`docker stop cloak-otel-lgtm`), then `curl -s -o /dev/null -w '%{http_code}' localhost:8080/actuator/health`
and a `/v1/me` call → both still serve (expect 200/401, **not** a hang or 5xx). This confirms the OTLP
exporters are async/non-blocking and the app fails open when the Collector is down. Restart the
container afterward. (OTLP exporters are non-blocking by default; this is a confirmation, and if any
call blocks, switch the exporter to async/batching and record it.)

- [ ] **Step 3: Document the backend in `ARCHITECTURE_GUIDE` §10**

Add a short subsection recording the chosen backend: app-level OTLP-push → OTel Collector seam → local
`grafana/otel-lgtm`; metrics (Micrometer-OTLP), traces (Micrometer Tracing + OTel), logs (structured +
OTLP); the privacy rules (no ciphertext/body/PII; `sub` never a metric label); and that swapping to
cloud is a Collector re-point. Keep §10.1–10.4 as the app-side contract; add §10.5 "Backend & local
stack".

- [ ] **Step 4: Document local viewing in `server/README.md`**

Add an `## Observability` section: `./dev.sh up` starts `otel-lgtm`; Grafana at `http://localhost:3000`;
the server exports metrics/logs/traces over OTLP; how to find a request by its `X-Trace-Id` in Grafana.

- [ ] **Step 5: Re-read both docs for accuracy, then commit**

```bash
git add README.md docs/ARCHITECTURE_GUIDE.md ../docs/superpowers/plans/2026-06-11-observability-foundation.md
git commit -m "$(printf 'docs(obs): document the observability backend and local viewing\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

## Definition of Done (this plan)

- `./dev.sh up` starts `otel-lgtm`; the server exports metrics, logs, and traces over OTLP.
- A request and the message round-trip produce a **connected trace** (WS/HTTP → JDBC → Kafka), visible
  in Grafana/Tempo; logs are correlated to traces by `traceId` in Loki; custom use-case metrics + Kafka
  lag + Hikari pool are in Prometheus.
- The API envelope `traceId` / `X-Trace-Id` **equals the OTel trace id**.
- The privacy test passes — no ciphertext/body/PII in any signal; `sub` is never a metric label.
- `./gradlew check` green, coverage ≥90%; one starter Grafana dashboard provisioned.
- `ARCHITECTURE_GUIDE` §10 + `server/README.md` document the backend and local viewing.

## Deferred (per the design spec)

- **Infra-level telemetry** — Kafka broker (JMX / broker-side lag) and Postgres (`pg_stat_*`) via OTel
  Collector receivers. *(Requested fast-follow.)*
- **Curated dashboard suite + SLOs/alerting** (beyond the one starter dashboard).
- **Standalone production Collector** + cloud-backend exporters (the seam is designed for it).
- **iOS-side observability** (separate, client-scoped effort).
