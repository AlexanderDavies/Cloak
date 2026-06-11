# obs — local observability stack

A single-container [`grafana/otel-lgtm`](https://github.com/grafana/docker-otel-lgtm) bundle: an
OpenTelemetry Collector plus Prometheus (metrics), Loki (logs), Tempo (traces), and Grafana to
visualise them. It is the local stand-in for the production OTel Collector seam — in production the
server exports to a standalone Collector instead, with no application change.

## What it gives you

The server exports **metrics, logs, and traces over OTLP** to this stack:

- OTLP gRPC — `localhost:4317`
- OTLP HTTP — `localhost:4318`
- Grafana UI — `http://localhost:3000` (anonymous admin, **local dev only**)

In Grafana you can find a single request by its `X-Trace-Id` (Tempo), see correlated logs (Loki), and
view service metrics (Prometheus).

## Dashboard

A starter **"Cloak server — overview"** dashboard is provisioned automatically (HTTP rate / latency /
errors, messages-routed rate, Kafka consumer lag, Hikari pool, JVM heap & threads, plus a link to
Tempo traces). It lives in `dashboards/cloak-server-overview.json` and is mounted into Grafana by the
provider in `grafana/provisioning/dashboards/cloak.yaml`; panels populate once the server is running
and traffic flows.

## Run

Started together with the rest of the local infra from the repo root:

```bash
./dev.sh up      # starts db, queue, iam, obs
./dev.sh ps
./dev.sh down
```

Or on its own:

```bash
docker compose -f obs/docker-compose.yml up -d
```
