# Slice 2 — User lookup (REST)

Resolves a human-friendly handle (username or primary e-mail) to the Keycloak `sub`
and primary device id needed to address an encrypted message envelope.

## Request — `GET /v1/users/lookup?handle=<handle>`

Bearer-authenticated (`Authorization: Bearer <Keycloak access token>`, `aud: cloak-api`).
`handle` is an **exact** username or primary e-mail address — no partial matching, no
wildcards, no substring search.

## Response — `200 OK`

```json
{
  "sub": "string (Keycloak UUID subject identifier)",
  "deviceId": 1
}
```

- `sub` — the Keycloak `sub` to use as `toSub` in a WebSocket message envelope.
- `deviceId` — the primary device number (integer; `1` for the primary device).

## Error responses

| Status | Meaning |
|--------|---------|
| `404 Not Found` | No user found with that exact handle. |
| `429 Too Many Requests` | Rate limit exceeded. |

## Server behaviour

- Resolved via the Keycloak Admin API with `exact=true`; substring / prefix matches are
  explicitly excluded.
- Rate-limited per authenticated caller to prevent enumeration.
- Unauthenticated lookup is not permitted — the caller must present a valid access token.
- Returns the `sub` of the **first** matched user (handles are unique in the Keycloak realm).

## Cleartext justification (root CLAUDE.md principle 6)

The response exposes the minimum routing identity required to send a message:

- `sub` — required to address the WebSocket envelope (`toSub`) and to fetch the recipient's
  prekey bundle (`GET /v1/keys/{sub}`). Without it, the sender cannot route a message.
- `deviceId` — required for the prekey bundle fetch (Slice 2, multi-device in Slice 5).

No display name, contact metadata, or relationship data is returned. The endpoint is
exact-match only and rate-limited to minimise the enumeration surface. The mapping from
a human-readable handle to a `sub` is inherent in any delivery-addressed messaging system
and is justified solely by that routing requirement.
