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
| `cloak-server-admin` | Confidential, service account (client credentials) | **Slice 2 — server-side user lookup.** The server calls Keycloak's Admin REST API to resolve a handle to a `sub`. Requires the `view-users` realm role. Configured via `cloak.keycloak-admin.*` in `application.yml`. **Never used by clients.** |

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

## Login theme

The `cloak` realm uses the **Cloak branded login theme** (`themes/cloak/login/`), which extends the stock Keycloak `keycloak` theme with an overridden page shell plus OneUI brand CSS (purple primary `#6D28D9`, green accents `#22C55E`, 10 px radius inputs/buttons, light + dark via `prefers-color-scheme`). The theme consists of:

| File | Purpose |
|------|---------|
| `themes/cloak/login/theme.properties` | Declares parent = `keycloak`, injects `cloak.css` |
| `themes/cloak/login/template.ftl` | Page shell forked from Keycloak's base login template; renders the Cloak logo `<img>` in the header (the stock shell shows the realm name as text). Keep in sync with the base template on Keycloak upgrades. |
| `themes/cloak/login/resources/css/cloak.css` | OneUI brand CSS (light + dark) |
| `themes/cloak/login/resources/img/logo.svg` | Cloak wordmark SVG (purple + green) |

The theme is mounted into the local Keycloak container via the volume in `docker-compose.yml` (`./themes/cloak:/opt/keycloak/themes/cloak:ro`).

### Dev iteration tip

`KC_SPI_THEME_CACHE_THEMES=false` (set in `docker-compose.yml`) disables Keycloak's theme cache so CSS/template edits are reflected on the next page load without restarting the container. Also set `KC_SPI_THEME_STATIC_MAX_AGE=-1` to prevent browser caching during development (both are already set in `docker-compose.yml`).

## Architecture & conventions

See [`CLAUDE.md`](CLAUDE.md) for the auth contract the server depends on, dev-vs-prod differences, and operating principles.
