# Cloak — IAM (Keycloak)

Keycloak, run as a Docker container in dev mode. Identity & access management for Cloak: owns user accounts/credentials and issues OIDC (OAuth 2.0) tokens. The iOS app authenticates here; the server validates the resulting JWTs. Keycloak handles identity only — it never sees message content.

## Prerequisites

- Docker

## Run locally

From the repo root (starts the infra stack):

```bash
./dev.sh up
```

Or IAM-only, from this folder:

```bash
docker compose up -d   # starts Keycloak on localhost:8081 and imports the cloak realm
docker compose down     # stop
```

- Admin console: `http://localhost:8081` — `admin` / `admin` (local dev only)
- OIDC discovery: `http://localhost:8081/realms/cloak/.well-known/openid-configuration`

## Realm

The `cloak` realm is auto-imported from [`realm/cloak-realm.json`](realm/cloak-realm.json) (the single source of truth):

| Client | Type | Purpose |
|--------|------|---------|
| `cloak-ios` | Public, PKCE (S256) | The iOS app — redirect `com.cloak.app://oauth-callback/*` |
| `cloak-api` | Bearer-only | The server as resource server; tokens carry `aud: cloak-api` |
| `cloak-test` | Public, direct-access grants | **Test-only.** Used exclusively to mint tokens in integration tests (direct password grant). Not used by the app. |

### Seeded users (local dev only)

The realm seeds two users — used both by the server integration tests (direct grant) and to **sign into the app locally**:

| Username | Password | `sub` (stable, = Keycloak user id) |
|----------|----------|------------------------------------|
| `alice` | `password` | `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa` |
| `bob` | `password` | `bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb` |

The `sub`s are **fixed in the realm import** so they're stable across re-imports and usable as message
recipients (the server routes by `sub`). These users exist for local dev and automated testing only —
**never in production**. (Admin console: `admin`/`admin`, also local-dev only.)

Edit `cloak-realm.json` to change realm config (config-as-code). Changing the realm name, client ids, redirect scheme, or audience is a contract change — coordinate with the app and server.

## Architecture & conventions

See [`CLAUDE.md`](CLAUDE.md) for the auth contract the server depends on, dev-vs-prod differences, and operating principles.
