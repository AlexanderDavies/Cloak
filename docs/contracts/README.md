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

- phase0-message-envelope.md — WebSocket message envelope (Phase 0).
- slice1-device-key-bundle.md — PUT /v1/keys public prekey bundle (Slice 1).
