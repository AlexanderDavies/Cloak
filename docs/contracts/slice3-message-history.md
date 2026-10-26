# Slice 3 — Message history backlog (REST)

Stateless keyset-paginated pull of a caller's routed messages, used to backfill local
(SQLCipher) history after install, relaunch, or a gap in WebSocket connectivity. The server
holds **no per-client delivery state** for this endpoint — every call is a self-contained
page request driven entirely by the `cursor` the caller supplies.

This complements, and does not replace, the real-time `/ws` delivery frame
(`slice3-message-envelope.md`): `/ws` pushes messages as they arrive; `GET /v1/messages`
lets a client catch up on anything it missed.

## Request — `GET /v1/messages?cursor=<opaque>&limit=<n>`

Bearer-authenticated (`Authorization: Bearer <Keycloak access token>`, `aud: cloak-api`).
The recipient is the authenticated JWT `sub` — there is no caller-supplied recipient
parameter (structurally prevents pulling another user's messages).

| Param | Required | Meaning |
|-------|----------|---------|
| `cursor` | No | Opaque token from the `cursor` field of the last consumed message in a prior page (stored, or skipped as permanently undecryptable). **Omit `?cursor` for the first pull** (starts from the beginning). |
| `limit` | No | Page size. Defaults to `50`. Server-capped at `200` regardless of what the caller requests. |

## Response — `200 OK`

Standard `WrappedResponse` envelope (`data` / `errors` / `traceId` — see
`server/src/main/java/com/cloak/server/common/web/WrappedResponse.java`).

```json
{
  "data": {
    "messages": [
      {
        "messageId":      "uuid",
        "fromSub":        "string (Keycloak sub of sender)",
        "fromDeviceId":   1,
        "toDeviceId":     1,
        "messageType":    3,
        "conversationId": "string (lowercase sha256 hex, 64 chars)",
        "ciphertext":     "base64 (opaque, never decrypted by the server)",
        "cursor":         "string (opaque; pass back as ?cursor= to resume after this message)"
      }
    ],
    "hasMore": false
  },
  "errors": null,
  "traceId": null
}
```

There is no `toSub` field on each message — the recipient is always the authenticated
caller, so it would be redundant.

### Ordering and pagination

- Messages are ordered by `(created_at, id)` ascending — the server's insertion order, not
  the inner-payload `sentAt` (which the server cannot read). Display-order sorting by
  `sentAt` happens client-side once the payload is decrypted.
- `cursor` is a keyset token encoding `(created_at, id)` of its row, **not** a database
  primary key or offset — no global `bigserial` is exposed to clients.
- The client passes the last consumed message's `cursor` back as `?cursor=` to resume. A
  message the client can never decrypt counts as consumed (it is skipped and the user told), so
  it cannot pin the cursor; only a transient client-side failure holds the cursor for a retry. `hasMore: true` indicates more pages are available; the client keeps pulling until
  `hasMore: false`.
- Stateless: repeating a request with the same `cursor` returns the same page (barring new
  messages arriving after it, which only affects pages requested later in the keyset order).
  The server tracks no per-client "last delivered" pointer for this endpoint.
- **Settle window:** rows younger than `cloak.messages.backlog-settle-lag` (default 3s, measured on
  the database clock) are withheld. `created_at` is the inserting transaction's start time, so a
  row can commit stamped behind a cursor already handed out; waiting out the window guarantees it
  never lands behind one. Clients should pull once more shortly after the window (the iOS list does,
  ~4s after its first pull), since live delivery only reaches devices connected at routing time.

## Cleartext-field justification (principle 6)

| Field | Justification |
|-------|--------------|
| `messageId` | Deduplicate against messages already received over `/ws` or a prior backlog page. |
| `fromSub` | Identify the sender so the recipient can group and display the message; same justification as the `/ws` delivery frame's `fromSub`. |
| `fromDeviceId` | Locate the correct Signal session for decryption. |
| `toDeviceId` | Confirm the message was addressed to this device (multi-device targeting, Slice 5). |
| `messageType` | Select the correct decrypt path (`SignalMessage` vs `PreKeySignalMessage`). |
| `conversationId` | Group the message into local history without the server needing plaintext; see `slice3-message-envelope.md` for the full justification (unchanged — same field, same reasoning, just read back instead of written). |
| `ciphertext` | Opaque; the only content field; encrypted on-device. |
| `cursor` | Opaque pagination token; required for the client to resume a keyset-paginated pull without exposing the underlying `(created_at, id)` as a raw, guessable primary key. |
| `hasMore` | Lets the client know whether to keep pulling; carries no information about message content. |

No plaintext, no delivery/read receipts (deferred to Slice 8), no per-client server-side
pull-state.

## Server behaviour

- Stateless: no server-side "last delivered to this client" bookkeeping. The client alone
  tracks which `cursor` it has consumed (Task C5, backlog cursor store).
- A message routed but never fetched over `/ws` (recipient offline) is retrievable here —
  this endpoint is the backstop for the Kafka fan-out path, not a duplicate of it.
