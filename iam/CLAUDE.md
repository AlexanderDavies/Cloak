# CLAUDE.md — Cloak IAM (Keycloak)

Keycloak is Cloak's **identity & access management** system. It owns user accounts and credentials and issues OpenID Connect (OAuth 2.0) tokens. It runs as a Docker container for local development. The iOS app authenticates against Keycloak and receives a JWT; the server validates that JWT (issuer + signature + audience) but never manages passwords.

**Keycloak handles identity only — it never sees message content.** E2EE keeps it outside the trust boundary for message confidentiality: even though it authenticates users, it cannot read messages (see root `Cloak/CLAUDE.md` for the E2EE/privacy invariants). Postgres references users only by their Keycloak subject (`sub`); no account or profile data is duplicated there.

## What lives in this folder

| Path | Purpose |
|------|---------|
| `docker-compose.yml` | Local Keycloak container (dev mode, embedded storage, realm auto-import) |
| `realm/cloak-realm.json` | **Realm export — the single source of truth for the realm, clients, and mappers** |
| `README.md` | How to run Keycloak locally |
| `CLAUDE.md` | This guide |

## Realm & clients

Realm: **`cloak`**. Issuer (local): `http://localhost:8081/realms/cloak`.

| Client | Type | Purpose |
|--------|------|---------|
| `cloak-ios` | Public, **PKCE (S256)**, standard flow | The iOS app. No client secret (mobile clients can't keep one). Redirect: `com.cloak.app://oauth-callback/*` |
| `cloak-api` | Bearer-only | Represents the server as a resource server. The `cloak-ios` client adds `cloak-api` to the token `aud` via an audience mapper, so the server can validate audience |

The server validates: **issuer** = `…/realms/cloak`, **signature** against the realm JWKS (`…/realms/cloak/protocol/openid-connect/certs`), and **audience** = `cloak-api`.

## What the server relies on (the contract)

- OIDC discovery: `http://localhost:8081/realms/cloak/.well-known/openid-configuration`
- The `sub` claim is the **stable user identifier** used everywhere downstream (DB rows, Kafka keys). Don't key off email or username.
- Token audience includes `cloak-api`.

Changing realm name, client ids, the redirect scheme, or the audience mapper is a **contract change** — coordinate with the app and server before editing `cloak-realm.json`.

## Security & environment notes

- **Dev mode only here.** `start-dev` uses embedded H2 storage and HTTP, so the folder is self-contained and disposable. Admin `admin`/`admin` is a **local-dev credential** — never used elsewhere.
- **Production differs and is not configured here:** external database, HTTPS/`KC_HOSTNAME`, secrets via a secret manager, `start` (not `start-dev`), and realm config managed as code rather than ad-hoc admin-console edits.
- **Realm changes are config-as-code.** Edit `realm/cloak-realm.json` (or re-export after admin-console changes) so the realm is reproducible. Don't rely on undocumented manual console state.

## Operating principles — apply on every cycle

Same principles as the rest of Cloak (`server/CLAUDE.md` §0):

1. **Ask, never assume.** Unclear client type, flow, redirect URI, scope, claim, or audience → stop and ask via `AskUserQuestion`. Never guess the auth contract.
2. **Validate every assumption.** Bring Keycloak up, hit the discovery URL, and decode a real token before declaring done.
3. **TDD.** Auth behaviour is driven by a failing server integration test that validates a real Keycloak-issued token (Testcontainers' Keycloak module), then made green.
4. **DRY · KISS · SOLID.** `cloak-realm.json` is the one source of truth for the realm; the simplest client setup that satisfies the flow; no speculative clients, roles, or mappers.
5. **Every change updates the README.** Any change to how Keycloak is run, configured, or what the realm exposes updates `iam/README.md` in the same change set.

## Local run

See `README.md`. In short: `../dev.sh up` (from repo root) starts Keycloak and imports the realm; or `docker compose up -d` here for IAM-only iteration. Admin console: `http://localhost:8081` (`admin`/`admin`).
