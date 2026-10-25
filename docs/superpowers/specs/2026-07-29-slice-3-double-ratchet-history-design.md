# Slice 3 — Double Ratchet conversation + history — Design

- **Date:** 2026-07-29
- **Status:** Implemented (2026-07-30)
- **Branch:** `feature/slice-3-double-ratchet-history`
- **Roadmap:** Slice 3 of `2026-06-08-cloak-mvp-roadmap-design.md`
- **Builds on:** Slice 2 (`2026-06-27-slice-2-x3dh-design.md`) — X3DH/PQXDH session, `SessionStore` on GRDB+SQLCipher, integer device numbers, `messageType` envelope field
- **iOS architecture:** `app/docs/ARCHITECTURE_GUIDE.md` (§3, §5, §6, §7, §11)
- **Server architecture:** `server/docs/ARCHITECTURE_GUIDE.md` (§5, §6, §9, §12)

## Goal

Turn the single-round-trip proof of Slice 2 into a **real conversation**: full back-and-forth with
per-message Double-Ratchet stepping (forward secrecy), **local encrypted history that survives
relaunch**, a **conversation list** with locally-decrypted last-message previews, and **out-of-order
tolerance**. The server persists complete ciphertext rows and exposes a **stateless backlog-pull**
endpoint so a client can catch up on messages it missed while a thread was closed. Message *delivery*
stays live-only in this slice; durable, in-order, effectively-once delivery is Slice 4.

## Key insight — "decrypt once" shapes the whole slice

Double-Ratchet decryption is **destructive**: once the recipient decrypts a message the ratchet
advances and that same ciphertext can **never be decrypted again**. Therefore a device **cannot**
restore its own history by re-fetching ciphertext from the server. The **plaintext must be persisted
locally the moment it is decrypted** (exactly why the iOS guide mandated SQLCipher-at-rest). The
server's stored ciphertext is a **delivery queue for not-yet-received messages**, not a re-readable
archive. This is the load-bearing decision behind everything below.

## Locked decisions

| Decision | Choice |
|----------|--------|
| **History source of truth** | **Local plaintext is authoritative.** iOS persists each decrypted message as plaintext in the SQLCipher DB; relaunch restores threads + conversation list **without the network**. The server never re-serves already-decrypted history to the same device (decrypt-once). |
| **Server history endpoint** | Build a **stateless backlog-pull** `GET /v1/messages` now. Its Slice-3 consumer: a client pulling ciphertext addressed to it that it has **not yet decrypted** (messages that arrived while the thread was closed / app not running). |
| **Backlog cursor & pagination** | **Keyset pagination on `(created_at, id)`** — `WHERE (created_at, id) > cursor ORDER BY created_at, id LIMIT N`, returning an opaque `nextCursor`. **No global `bigserial`** (a client-visible global counter leaks system-wide message volume — a privacy leak). The client stores its high-water mark = the last `(created_at, id)` pulled. Server is **stateless** about who-received-what; durable delivery-state is Slice 4. |
| **Conversation identity** | **Deterministic canonical key**: `conversation_id = sha256_hex(sort(member_subs).join)`. For 1:1 = `hash(sort([senderSub, recipientSub]))`. Both participants compute the identical id independently — "one chat per unique member set" falls out for free, and it generalises to N members for future group chat. **Carried in the envelope** (forward hook: a future group message's membership is not recoverable from the routing pair, so the sender must declare its conversation) **and persisted** on `encrypted_message`. |
| **conversation_id validation** | The server **validates** `conversation_id == hash(sort(fromSub, toSub))` for 1:1 and rejects a mismatch (400), so a client cannot spoof an arbitrary grouping. |
| **Message ordering — display** | **Sender-authoritative timestamp carried *inside* the encrypted payload.** A small inner struct `MessageContent { sentAt, text }` is serialized and encrypted as the Signal plaintext. The client sorts a thread by `sentAt`; sent and received messages merge-sort into one timeline. Timing metadata never reaches the server (principle 6). |
| **Message ordering — cursor** | Server `created_at` (+ `id` tiebreak) — used only for the backlog cursor/keyset and operational needs, never as the user-facing timestamp. |
| **Out-of-order handling** | libsignal already decrypts arrivals in any order (skipped-message keys, working since Slice 2). The UI **inserts each decrypted message at its `sentAt`-sorted position** rather than appending, so a late arrival slots into place. |
| **Message-status lifecycle** | Slice 3 ships **Sending → Sent → Failed** (sender-side, local only). **Delivered/Read** receipts are **Slice 8** (dedicated receipts topic). **Display:** glyph + text label (design system), inside the bubble after the time — Sending/Sent only on the latest outgoing message (kept after the peer replies); Failed on every failed message, always. Retry is Slice 4. |
| **Thread dates** | Messages are grouped under a centred, unobtrusive day separator wherever the date changes: "Today", "Yesterday", the weekday within the last six days, else a date (with the year only when it differs). Each message shows its time inside the bubble, on its own line beneath the text, right-aligned; list rows show today's time or the day label. |
| **Vestigial `device_id` UUID** | Flyway V4 **drops** the always-null `encrypted_message.device_id UUID` column (destructive-but-planned per `db/CLAUDE.md`; pre-release, dev data disposable), replacing it with integer device-number columns. |
| **MessageRowMapper sentinels** | **Deleted.** V4 persists real `sender_device_id` / `recipient_device_id` / `message_type` / `conversation_id`, so the read-back path returns real values (the Slice-2 resume-blocker). |

## Scope

**In scope**

- Continuous 1:1 conversation — many messages each way, ratchet stepping per message (already provided by libsignal; this slice makes the app *use* it continuously rather than one-shot).
- Local encrypted message history in the existing SQLCipher DB; relaunch restores threads + list offline.
- Conversation list populated from local history with decrypted last-message previews + timestamps.
- Server: Flyway V4, complete ciphertext persistence (real device numbers, `message_type`,
  `conversation_id`), `conversation_id` validation, stateless `GET /v1/messages` backlog-pull endpoint.
- Inner encrypted payload `{ sentAt, text }`; sort/insert by `sentAt`; out-of-order insertion.
- Contract fixtures: `slice3-message-envelope` (adds `conversationId`), new `slice3-message-history`.

**Out of scope (deferred — with homes)**

| Deferred | Home |
|----------|------|
| Durable server delivery-state, acks, server-side dedupe, deliver-on-reconnect, effectively-once | **Slice 4** |
| Multi-device fan-out / self-sync (per-device sessions, own-device copies) | **Slice 5** |
| Delivered/Read receipts + indicators (receipts Kafka topic) | **Slice 8** |
| Prekey replenishment / rotation | **Slice 9** |
| Group chat (the `ConversationKey`/`conversation_id` decision is the forward hook only) | **TBC (future initiative)** |

## Architecture

### Server (Hexagonal + DDD, guide §5/§6)

- **Flyway V4** on `encrypted_message`:
  - Add `conversation_id TEXT NOT NULL`, `sender_device_id INT NOT NULL`,
    `recipient_device_id INT NOT NULL`, `message_type SMALLINT NOT NULL`.
  - **Drop** `device_id UUID` (always null; replaced by the integer columns above).
  - Keep `idx_message_recipient (recipient_sub, created_at)`; the backlog keyset uses
    `(recipient_sub, created_at, id)`.
- **Domain `Message`** already carries `senderDeviceId` / `recipientDeviceId` / `messageType`; add
  `conversationId`. Delete both `MessageRowMapper` sentinels; map the real columns both directions.
- **Inbound path** (existing WS ingest): DTO gains `conversationId`; use case validates
  `conversationId == canonical(sort(fromSub, toSub))` and rejects mismatch → 400 via the global
  handler (§9). Persist the complete row, publish as today.
- **New read port + adapter + controller**: `GET /v1/messages?cursor=<opaque>&limit=N`.
  - Recipient = authenticated JWT `sub`; returns delivery frames addressed to that `sub`,
    `ORDER BY created_at, id` keyset from the decoded cursor, capped `limit` (server-enforced max).
  - Response is the standard `WrappedResponse<MessageHistoryPage>` with `data.messages[]` +
    `data.nextCursor` (null when drained). Opaque cursor = base64 of `(created_at, id)` so clients
    don't couple to internals.
  - **Stateless** — no server delivery-state, no mutation. Read-only query adapter.

### iOS (layered modules, guide §3/§5/§6/§7/§11)

- **`ConversationKey`** (`CloakFoundation…` or a small domain type): `of([String]) -> String` =
  `sha256_hex(sorted(subs).joined(separator:))`. Single source of the hash for envelope + local key.
- **Inner payload** (`CloakDataMessaging` or crypto boundary): `MessageContent { sentAt: Date, text:
  String }`, Codable. `ChatThreadViewModel.send` builds `MessageContent(sentAt: .now, text:)`,
  serializes, encrypts via existing `MessageCrypting`. Receive path deserializes after `decrypt`.
- **Local history** (`CloakDataMessaging`): GRDB `message` table in the existing SQLCipher DB.
  Columns: `message_id` (PK), `conversation_id`, `peer_sub`, `is_mine`, `sent_at`, `body` (plaintext,
  at-rest-encrypted by SQLCipher), `status`, `server_created_at` (nullable for own-sent). Protocol
  **`MessageRepository`**: `save`, `loadThread(conversationId, paging)`,
  `latestPerConversation()`, `highWaterMark()` (max `(server_created_at, message_id)` seen).
- **`BacklogSync`** (`CloakData…`): on launch + foreground, `GET /v1/messages` from the stored
  high-water mark; for each frame **decrypt-once** (inbound `.preKey` establishes the session as a
  side effect), build `MessageContent`, persist. **Idempotent on `message_id`** so backlog/live
  overlap never duplicates. Advances the high-water mark as it consumes; a frame that can never be
  decrypted is skipped (the mark moves past it) and the count is surfaced to the user, while a
  transient local failure holds the mark for a retry.
- **`ChatThreadViewModel`** (`CloakFeatureConversation`): no longer ephemeral — loads the thread from
  `MessageRepository` on open. `send` = persist(`sending`) → transport send → mark `sent` (or
  `failed`). Receive (live or backlog) = persist + **insert by `sentAt`** position.
- **`ConversationListViewModel`**: `latestPerConversation()` → rows with decrypted preview text +
  `sentAt`; tapping opens the thread. Replaces the current empty-state list.

## Data flow

- **Send:** compose → `MessageContent{ sentAt: now, text }` → serialize → `encrypt` → envelope
  `{…, conversationId, messageType }` → persist local (`sending`) → WS send → mark `sent`.
- **Receive (live, WS delivery frame):** dedupe by `messageId` → `decrypt` → deserialize
  `MessageContent` → persist → insert by `sentAt`.
- **Receive (backlog, on launch/foreground):** `GET /v1/messages` since high-water → per frame
  decrypt-once + persist (dedupe by `messageId`) → advance high-water mark (undecryptable → skip,
  count, advance; transient local failure → stop, retry next pull).
- **Relaunch:** conversation list + open thread render from local SQLCipher **before any network**;
  a backlog pull then fills anything missed while the app was down.

## Error handling (guide §11 / §9)

- Send transport failure → message stays `failed` locally and is surfaced on the VM; nothing silently
  dropped. (Automatic retry/queue is Slice 4.) A send interrupted by the app being killed is failed at
  the next launch (`failInterruptedSends`), so it never shows "Sending…" forever.
- Decrypt succeeded but the local save failed → the save is retried in place (`saveRetrying`); the
  key is already spent, so if it still fails the live thread keeps showing the message for the
  session (with an error) and the backlog's next sync reports it as skipped. Atomic decrypt + save is
  a follow-up (see Open risk).
- Concurrency: every libsignal operation runs under one per-store lock
  (`SignalKeyStore.exclusively`), so backlog sync, live receive and sending can never overwrite each
  other's ratchet state; a backlog decrypt that loses the race to live delivery counts as delivered.
- Backlog settle window: the server withholds rows younger than `backlog-settle-lag` (3s), because
  `created_at` is the transaction start time and a late commit could otherwise land behind a
  client's cursor; the conversation list pulls once more ~4s after its first pull.
- Undecodable delivery/backlog frame or decrypt failure → logged (no plaintext/keys) and skipped.
  On the backlog, an undecryptable frame is permanent (the ratchet spends the key on the first attempt),
  so the high-water mark advances past it and the user is told how many were skipped — holding the mark
  would wedge every later message. Only a transient local failure (dedupe check / persist) holds the
  mark so it is retried on the next pull.
- Server: `conversation_id` mismatch → 400; malformed cursor → 400; all via the global handler.

## Privacy (root principle 6) check

- **New cleartext (wire + DB):** `conversation_id` (a pure function of `sender_sub` + `recipient_sub`,
  which the server already sees → **no new information**, and validated); `sender_device_id` /
  `recipient_device_id` / `message_type` (routing/decrypt metadata, justified in Slice 2);
  `created_at` (server operational + cursor).
- **Stays encrypted:** `sentAt` and `text` live inside the encrypted payload on the wire and inside
  the SQLCipher-encrypted DB at rest. The server never sees message text or the sender's send-time.
- **No plaintext in logs**, Postgres columns, or Kafka. Local plaintext exists only inside the
  SQLCipher DB, keyed by the Keychain secret.

## Testing (DoD)

- **Server (Testcontainers, guide §12):** real fields persisted + read back (no sentinels); backlog
  keyset pagination — ordering, `limit`, cursor round-trip, drain returns `nextCursor: null`;
  recipient scoping (only my messages); `conversation_id`-mismatch rejection; privacy assertion (no
  plaintext in row or log).
- **iOS (Swift Testing against mocks + a temporary GRDB DB, guide §14):** history persist + restore
  across a fresh store; out-of-order insert lands in `sentAt` order; `BacklogSync` dedupe +
  high-water advance + skip-and-count of undecryptable frames (never wedging the backlog) vs. stop on
  a transient persist/dedupe failure; conversation-list previews; `MessageContent` round-trip; send status
  transitions (`sending → sent`, `sending → failed`). ≥90% coverage (views/generated excluded).
- **Contract fixtures** updated/added and shared server→iOS so the mocks can't drift.
- **Two-simulator acceptance:** multi-message back-and-forth with forward secrecy; kill + relaunch
  both apps → history restored from local DB; send while the peer's thread is closed → peer pulls it
  on next open; Postgres holds ciphertext only.

## Open risk

- **Decrypt and save are not atomic.** A local write failure that outlasts `saveRetrying` loses the
  plaintext (the key is spent). Rare (SQLCipher write failure, e.g. disk full) and always surfaced
  to the user. Follow-up: run libsignal's store writes and the message insert in one transaction.
- **Rejected WS frames are not acknowledged to the sender.** A frame dropped for a
  `conversationId`/`messageType` mismatch still shows "Sent" locally. Only a client bug can cause
  it (the client computes both). Home: Slice 4's server→sender send confirmations.

- **Clock skew between sender devices** can momentarily misorder a rapid back-and-forth (sender-
  authoritative `sentAt` from two clocks). Accepted for MVP — every real 1:1 messenger lives with
  this, and it is strictly better than exposing timing metadata to the server. Revisit only if it
  proves visible in practice.
