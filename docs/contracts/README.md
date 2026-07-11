# Contracts

Single source of truth for client ↔ server contracts (REST/WebSocket envelopes,
Kafka record shapes). The **server is the authority**; iOS test fixtures are
copied/generated from these documents so the app's mocked `Service` cannot drift
from the real contract (iOS does not use Testcontainers — see `app/CLAUDE.md`).

Rules:
- Every cross-boundary shape used by a slice has a contract file here.
- A contract change is a deliberate, reviewed change — update the file in the
  same PR as the code, and refresh the iOS fixtures.
- Cleartext envelope fields carry routing/delivery metadata only; everything
  else is inside the encrypted payload (root `CLAUDE.md` principle 6).

## Contracts

- slice1-device-key-bundle.md — `PUT /v1/keys` public prekey bundle publish (Slice 1).
- slice2-user-lookup.md — `GET /v1/users/lookup` exact-match handle → sub (Slice 2).
- slice2-prekey-bundle.md — `GET /v1/keys/{sub}` prekey bundle fetch with PQXDH Kyber key (Slice 2).
- slice2-message-envelope.md — WebSocket message envelope with integer device ids and messageType (Slice 2; supersedes phase0-message-envelope).
