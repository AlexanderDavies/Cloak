# Slice 3 — Double Ratchet conversation + history — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn Slice 2's single encrypted round-trip into a persistent 1:1 conversation — local encrypted history that survives relaunch, a populated conversation list, out-of-order tolerance, and a stateless server backlog-pull endpoint.

**Architecture:** Local plaintext in SQLCipher is the authoritative history (decrypt-once). The server persists complete ciphertext rows (real device numbers, `messageType`, `conversationId`) and serves a keyset-paginated `GET /v1/messages` backlog. A deterministic `conversationId = sha256_hex(sort(member_subs))` is carried in the envelope and validated server-side. Display order comes from a sender-authoritative `sentAt` carried *inside* the encrypted payload.

**Tech Stack:** Server — Java 25, Spring Boot 4, Hexagonal + DDD, Flyway, JPA + JdbcClient, Avro/Kafka, Testcontainers. iOS — Swift 6, SwiftUI, `@Observable`, GRDB + SQLCipher, libsignal, Swift Testing.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-07-29-slice-3-double-ratchet-history-design.md` — authoritative.
- **Canonical conversation id (both sides, byte-identical):** `conversationId = lowercaseHex( SHA-256( UTF8( members.sorted().joined(separator: ":") ) ) )`. For 1:1, `members = [senderSub, recipientSub]`; sort lexicographically by Unicode scalar (ascending); join with a single `:`; SHA-256; lowercase hex.
- **Privacy (root principle 6):** cleartext on wire/DB limited to routing metadata (`conversationId`, device numbers, `messageType`, `created_at`). `sentAt` + message text stay encrypted (inside the payload on the wire, inside SQLCipher at rest). Never log ciphertext, plaintext, keys, tokens, or `sub` as a metric label.
- **Ordering:** display sorts by inner-payload `sentAt`; backlog cursor is keyset on `(created_at, id)`. **No global `bigserial` exposed to clients.**
- **Message status this slice:** `sending → sent → failed` only. Delivered/Read = Slice 8.
- **Server tests:** `@SpringBootTest` on Testcontainers (real Postgres/Kafka/Keycloak) — needs Docker. Run `./gradlew check` from `server/`.
- **iOS build/test:** workspace build — `cd app && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodegen generate && pod install`, then `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild test -workspace app/Cloak.xcworkspace -scheme Cloak -destination 'platform=iOS Simulator,name=iPhone 17' -test-timeouts-enabled YES -default-test-execution-time-allowance 60`. **Pre-boot the `iPhone 17` simulator** before testing. `swiftlint` needs `DEVELOPER_DIR` set. SwiftLint-strict: no force-unwraps (use `#require` in tests), no `String(decoding:as:)`.
- **Coverage ≥ 90%** (platform-edge adapters + pure views excluded, guide §14.5); Checkstyle/ArchUnit (server) + SwiftLint-strict (iOS) green.
- **Commit granularity:** one commit per task (red→green→refactor folded in). Do **not** open the PR or squash until Task D1.

---

## File structure

**Contracts (source of truth, server-authoritative):**
- `docs/contracts/slice3-message-envelope.md` + `fixtures/slice3-message-envelope.json` — adds `conversationId`.
- `docs/contracts/slice3-message-history.md` + `fixtures/slice3-message-history.json` — new backlog page shape.

**Server (new/modified):**
- `db/migrations/V4__message_history_columns.sql` — new.
- `.../domain/message/ConversationId.java` — new (canonical hash).
- `.../domain/message/Message.java`, `.../usecase/RouteMessageCommand.java`, `.../usecase/RouteMessageUseCase.java` — add `conversationId` + validation.
- `.../adapter/output/database/message/EncryptedMessageEntity.java`, `MessageRowMapper.java` — real columns.
- `.../adapter/input/websocket/InboundEnvelope.java`, `MessageWebSocketHandler.java` — add `conversationId` + drop-on-mismatch.
- `server/src/main/avro/OutboundEnvelope.avsc`, `KafkaMessagePublisherAdapter.java`, `OutboundMessageConsumer.java` — add `conversationId`.
- `.../port/output/message/MessageHistoryQueryPort.java`, `.../adapter/output/database/message/MessageHistoryQueryAdapter.java`, `MessageCursor.java`, `.../usecase/FetchMessageHistoryUseCase.java`, `.../adapter/input/rest/messages/MessageHistoryController.java`, `MessageHistoryResponse.java` — new backlog endpoint.

**iOS (new/modified):**
- `app/Cloak/Foundation/Encryption/ConversationKey.swift`, `MessageContent.swift` — new.
- `app/Cloak/Foundation/Network/MessageTransport.swift` — `MessageEnvelope` gains `conversationId`.
- `app/Cloak/Foundation/Persistence/GRDBMessageRepository.swift`, `MessageRecord.swift`, `GRDBBacklogCursor.swift` — new local history.
- `app/Cloak/Feature/Conversation/MessageHistoryClient.swift`, `BacklogSync.swift` — new backlog pull.
- `app/Cloak/Feature/Conversation/ChatThreadViewModel.swift` — persist + restore + sort.
- `app/Cloak/Feature/Conversations/ConversationListViewModel.swift` + `ConversationListView.swift` — real list.
- `app/Cloak/Feature/Conversation/RootView.swift` — composition-root wiring.

---

## Task A1: Contracts + fixtures

**Files:**
- Create: `docs/contracts/slice3-message-envelope.md`
- Create: `docs/contracts/fixtures/slice3-message-envelope.json`
- Create: `docs/contracts/slice3-message-history.md`
- Create: `docs/contracts/fixtures/slice3-message-history.json`
- Create: `app/CloakTests/Fixtures/slice3-message-envelope.json` (copy)
- Create: `app/CloakTests/Fixtures/slice3-message-history.json` (copy)

**Interfaces:**
- Produces: the wire shapes every later task encodes/decodes against.

- [ ] **Step 1: Write `slice3-message-envelope.md`.** Document the inbound + delivery frames. Inbound (client→server, over `/ws`): `{ messageId, toSub, toDeviceId, fromDeviceId, messageType, conversationId, ciphertext }` — same as slice2 **plus `conversationId`** (server validates it equals the canonical hash of `{sender, recipient}` and drops the frame on mismatch). Delivery (server→client): adds server-stamped `fromSub`. State the canonical-hash algorithm verbatim from Global Constraints. Note `ciphertext` is base64 of the encrypted inner payload `{ sentAt, text }`.

- [ ] **Step 2: Write the envelope fixture** `docs/contracts/fixtures/slice3-message-envelope.json`:

```json
{
  "messageId": "11111111-1111-1111-1111-111111111111",
  "toSub": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
  "toDeviceId": 1,
  "fromDeviceId": 1,
  "messageType": 3,
  "conversationId": "d6f3...replace_with_real_sha256_hex...",
  "ciphertext": "BASE64_OPAQUE",
  "fromSub": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
}
```

Compute the real `conversationId`: `sha256_hex("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")` (subs already sorted ascending). Run:
```bash
printf '%s' 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb' | shasum -a 256
```
Paste the hex into the fixture.

- [ ] **Step 3: Write `slice3-message-history.md`.** Document `GET /v1/messages?cursor=<opaque>&limit=<n>` (bearer auth; recipient = JWT `sub`). Response is the standard `WrappedResponse` with `data = { messages: [ { messageId, fromSub, fromDeviceId, toDeviceId, messageType, conversationId, ciphertext, cursor } ], hasMore: <bool> }`. `cursor` is an opaque token; the client passes the last successfully-consumed message's `cursor` back as `?cursor=`. Omit `?cursor` for the first pull. `limit` defaults to 50, server-capped at 200. Ordered by `(created_at, id)` ascending. Stateless (no server delivery-state).

- [ ] **Step 4: Write the history fixture** `docs/contracts/fixtures/slice3-message-history.json`:

```json
{
  "data": {
    "messages": [
      {
        "messageId": "11111111-1111-1111-1111-111111111111",
        "fromSub": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "fromDeviceId": 1,
        "toDeviceId": 1,
        "messageType": 3,
        "conversationId": "REPLACE_WITH_REAL_SHA256_HEX",
        "ciphertext": "BASE64_OPAQUE",
        "cursor": "MTcyMjIwMDAwMDAwMDAwMDoxMTExMTExMS0xMTExLTExMTEtMTExMS0xMTExMTExMTExMTE="
      }
    ],
    "hasMore": false
  },
  "errors": null,
  "traceId": null
}
```
Use the same `conversationId` hex as Step 2.

- [ ] **Step 5: Copy both fixtures to iOS** (`app/CloakTests/Fixtures/`) byte-identical.

- [ ] **Step 6: Commit**
```bash
git add docs/contracts app/CloakTests/Fixtures/slice3-message-envelope.json app/CloakTests/Fixtures/slice3-message-history.json
git commit -m "docs(contracts): Slice 3 message envelope (conversationId) + history page"
```

---

## Task S1: Flyway V4 + real message columns

**Files:**
- Create: `db/migrations/V4__message_history_columns.sql`
- Modify: `server/src/main/java/com/cloak/server/adapter/output/database/message/EncryptedMessageEntity.java`
- Modify: `server/src/main/java/com/cloak/server/adapter/output/database/message/MessageRowMapper.java`
- Modify: `server/src/main/java/com/cloak/server/domain/message/Message.java` (add `conversationId`)
- Test: `server/src/test/java/com/cloak/server/adapter/output/database/message/MessageRepositoryAdapterIntegrationTest.java`

**Interfaces:**
- Consumes: `Message` domain (Task adds `conversationId()`).
- Produces: `Message.create(id, senderSub, recipientSub, conversationId, senderDeviceId, recipientDeviceId, messageType, ciphertext)`; `encrypted_message` columns `conversation_id TEXT`, `sender_device_id INT`, `recipient_device_id INT`, `message_type SMALLINT`; `device_id` dropped.

- [ ] **Step 1: Write the migration** `db/migrations/V4__message_history_columns.sql`:
```sql
-- Slice 3: persist the real routing/decrypt fields the read-back path needs, retiring the
-- always-null device_id UUID. Pre-release: no historical rows to backfill.
ALTER TABLE encrypted_message DROP COLUMN device_id;
ALTER TABLE encrypted_message ADD COLUMN conversation_id     TEXT     NOT NULL;
ALTER TABLE encrypted_message ADD COLUMN sender_device_id    INT      NOT NULL;
ALTER TABLE encrypted_message ADD COLUMN recipient_device_id INT      NOT NULL;
ALTER TABLE encrypted_message ADD COLUMN message_type        SMALLINT NOT NULL;

CREATE INDEX idx_message_conversation ON encrypted_message (conversation_id, created_at);
```

- [ ] **Step 2: Write the failing repository test.** Add to `MessageRepositoryAdapterIntegrationTest` (create the class if absent, following an existing `@SpringBootTest` Testcontainers test — e.g. the device-key repository test). Save a `Message` with real values, `find` it, assert every field round-trips (no sentinels):
```java
@Test
void persistsAndReadsBackRealRoutingFields() {
  var id = new MessageId(UUID.randomUUID().toString());
  var convo = ConversationId.canonical("alice-sub", "bob-sub");
  var message = Message.create(id, "alice-sub", "bob-sub", convo, 1, 1, 3,
      new Ciphertext(new byte[] {1, 2, 3}));
  repository.save(message);

  var found = repository.find(id).orElseThrow();
  assertThat(found.conversationId()).isEqualTo(convo);
  assertThat(found.senderDeviceId()).isEqualTo(1);
  assertThat(found.recipientDeviceId()).isEqualTo(1);
  assertThat(found.messageType()).isEqualTo(3);
  assertThat(found.ciphertext().value()).containsExactly(1, 2, 3);
}
```
> `ConversationId.canonical` is added in Task S2 Step 1 — if implementing S1 first, inline the literal hex or reorder so S2 Step 1 lands first. (Recommended order: S2 Step 1 defines `ConversationId`, then S1.)

- [ ] **Step 3: Run it — expect FAIL** (compile error: `Message.create` arity / missing columns).
```bash
cd server && ./gradlew test --tests '*MessageRepositoryAdapterIntegrationTest*'
```

- [ ] **Step 4: Add `conversationId` to `Message`.** Add the field, constructor param (after `recipientSub`), the factory param, and a `public String conversationId()` accessor with Javadoc. Keep the existing device/messageType fields.

- [ ] **Step 5: Rewrite `EncryptedMessageEntity`.** Remove the `deviceId` field/column/getter. Add columns:
```java
@Column(name = "conversation_id", nullable = false) private String conversationId;
@Column(name = "sender_device_id", nullable = false) private int senderDeviceId;
@Column(name = "recipient_device_id", nullable = false) private int recipientDeviceId;
@Column(name = "message_type", nullable = false) private short messageType;
```
Update the constructor to `(UUID id, String senderSub, String recipientSub, String conversationId, int senderDeviceId, int recipientDeviceId, short messageType, byte[] ciphertext)` and add getters. Update the class Javadoc.

- [ ] **Step 6: Rewrite `MessageRowMapper`.** Delete both sentinels and the TODO. Map real fields both ways:
```java
EncryptedMessageEntity toEntity(Message m) {
  return new EncryptedMessageEntity(
      UUID.fromString(m.id().value()), m.senderSub(), m.recipientSub(), m.conversationId(),
      m.senderDeviceId(), m.recipientDeviceId(), (short) m.messageType(), m.ciphertext().value());
}

Message toDomain(EncryptedMessageEntity e) {
  return Message.create(
      new MessageId(e.getId().toString()), e.getSenderSub(), e.getRecipientSub(),
      e.getConversationId(), e.getSenderDeviceId(), e.getRecipientDeviceId(),
      e.getMessageType(), new Ciphertext(e.getCiphertext()));
}
```

- [ ] **Step 7: Run the test — expect PASS.** Then run `./gradlew checkstyleMain` to catch missing Javadoc.

- [ ] **Step 8: Commit**
```bash
git add db/migrations/V4__message_history_columns.sql server/src/main/java/com/cloak/server/adapter/output/database/message server/src/main/java/com/cloak/server/domain/message/Message.java server/src/test
git commit -m "feat(server): persist real message routing fields + conversationId (Flyway V4); drop device_id"
```

---

## Task S2: conversationId end-to-end on ingest + validation

**Files:**
- Create: `server/src/main/java/com/cloak/server/domain/message/ConversationId.java`
- Modify: `server/src/main/java/com/cloak/server/usecase/RouteMessageCommand.java`
- Modify: `server/src/main/java/com/cloak/server/usecase/RouteMessageUseCase.java`
- Modify: `server/src/main/java/com/cloak/server/adapter/input/websocket/InboundEnvelope.java`
- Modify: `server/src/main/java/com/cloak/server/adapter/input/websocket/MessageWebSocketHandler.java`
- Test: `server/src/test/java/com/cloak/server/domain/message/ConversationIdTest.java`
- Test: existing WS integration test (the Slice 2 alice→bob round-trip) — extend to send `conversationId` + add a mismatch case.

**Interfaces:**
- Produces: `ConversationId.canonical(String a, String b) -> String` (lowercase sha256 hex of the sorted, `:`-joined pair). `RouteMessageCommand` gains `String conversationId` (after `recipientSub`). `RouteMessageUseCase.route` throws `IllegalArgumentException` on mismatch. `InboundEnvelope` gains `String conversationId`.

- [ ] **Step 1: Write the failing `ConversationIdTest`** with a known vector matching the fixture:
```java
@Test
void canonicalIsOrderIndependentAndMatchesSha256() {
  String a = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  String b = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  String expected = "<PASTE hex from Task A1 Step 2>";
  assertThat(ConversationId.canonical(a, b)).isEqualTo(expected);
  assertThat(ConversationId.canonical(b, a)).isEqualTo(expected); // order-independent
}
```

- [ ] **Step 2: Run — expect FAIL** (class missing). `./gradlew test --tests '*ConversationIdTest*'`

- [ ] **Step 3: Implement `ConversationId`:**
```java
package com.cloak.server.domain.message;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Deterministic canonical conversation id: lowercase SHA-256 hex of the sorted, ':'-joined subs. */
public final class ConversationId {
  private ConversationId() {}

  /**
   * Computes the canonical id for a 1:1 pair. Order-independent (members are sorted first).
   *
   * @param subA one member sub
   * @param subB the other member sub
   * @return lowercase SHA-256 hex of {@code sorted(subs).join(":")}
   */
  public static String canonical(String subA, String subB) {
    List<String> members = List.of(subA, subB).stream().sorted().toList();
    String joined = String.join(":", members);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e); // never on a JRE
    }
  }
}
```

- [ ] **Step 4: Run — expect PASS.**

- [ ] **Step 5: Thread `conversationId` through the command + use case.** Add `String conversationId` to `RouteMessageCommand` (after `recipientSub`). In `RouteMessageUseCase.route`, validate before building the message and pass it into `Message.create`:
```java
String expected = ConversationId.canonical(cmd.senderSub(), cmd.recipientSub());
if (!expected.equals(cmd.conversationId())) {
  throw new IllegalArgumentException("conversationId does not match sender/recipient");
}
var message = Message.create(new MessageId(cmd.messageId()), cmd.senderSub(), cmd.recipientSub(),
    cmd.conversationId(), cmd.senderDeviceId(), cmd.recipientDeviceId(), cmd.messageType(),
    new Ciphertext(cmd.ciphertext()));
```

- [ ] **Step 6: Add `conversationId` to `InboundEnvelope`** (record component after `messageType`) and update the class Javadoc to note it is validated, not trusted.

- [ ] **Step 7: Update `MessageWebSocketHandler.handleTextMessage`.** Pass `env.conversationId()` into the command, and wrap the `route` call so a validation failure drops the frame with a routing-only warning (WS ingest has no HTTP status; the frame is rejected by being dropped):
```java
InboundEnvelope env = jsonMapper.readValue(message.getPayload(), InboundEnvelope.class);
try {
  useCase.route(new RouteMessageCommand(
      env.messageId(), sub(session), env.toSub(), env.conversationId(),
      env.fromDeviceId(), env.toDeviceId(), env.messageType(),
      Base64.getDecoder().decode(env.ciphertext())));
} catch (IllegalArgumentException e) {
  // Rejected frame — never persisted/published. Log routing context only, never ciphertext.
  log.warn("Dropped inbound frame {}: {}", env.messageId(), e.getMessage());
}
```
Add an SLF4J `log` field to the handler if absent.

- [ ] **Step 8: Extend the WS integration test.** Update the existing alice→bob round-trip to include `conversationId = ConversationId.canonical(aliceSub, bobSub)` in the sent frame (assert bob still receives it). Add a test that a frame with a wrong `conversationId` results in **no delivery** to bob and **no persisted row** (query the repo / assert bob's inbound stream stays empty within a short timeout).

- [ ] **Step 9: Run the message + WS tests — expect PASS.**
```bash
cd server && ./gradlew test --tests '*ConversationIdTest*' --tests '*WebSocket*' --tests '*Route*'
```

- [ ] **Step 10: Commit**
```bash
git add server/src
git commit -m "feat(server): carry + validate conversationId on WS ingest; drop mismatched frames"
```

---

## Task S3: conversationId through Kafka delivery

**Files:**
- Modify: `server/src/main/avro/OutboundEnvelope.avsc`
- Modify: `server/src/main/java/com/cloak/server/adapter/output/kafka/message/KafkaMessagePublisherAdapter.java`
- Modify: `server/src/main/java/com/cloak/server/adapter/input/kafka/OutboundMessageConsumer.java`
- Test: existing consumer/delivery integration test.

**Interfaces:**
- Produces: delivery frame JSON gains `conversationId`; Avro `OutboundEnvelope` gains `conversationId` (string, default `""` for BACKWARD compatibility).

- [ ] **Step 1: Add the Avro field.** In `OutboundEnvelope.avsc`, after `messageType`:
```json
{ "name": "conversationId", "type": "string", "default": "", "doc": "canonical conversation id (sha256 hex of sorted member subs)" },
```
Keep `ciphertext` last. Regenerate: `./gradlew generateAvroJava compileJava`.

- [ ] **Step 2: Set it in the publisher.** In `KafkaMessagePublisherAdapter.publish`, add `.setConversationId(m.conversationId())` to the builder chain.

- [ ] **Step 3: Emit it in the delivery frame.** In `OutboundMessageConsumer.onOutbound`, add before the ciphertext line:
```java
payload.put("conversationId", env.getConversationId().toString());
```

- [ ] **Step 4: Update the delivery test.** In the consumer/end-to-end test, assert the forwarded frame JSON contains the expected `conversationId`. If a fixture asserts the exact frame, update it to the slice3 shape.

- [ ] **Step 5: Run — expect PASS.**
```bash
cd server && ./gradlew test --tests '*Outbound*' --tests '*Delivery*'
```

- [ ] **Step 6: Commit**
```bash
git add server/src/main/avro server/src/main/java server/src/test
git commit -m "feat(server): carry conversationId through Kafka delivery envelope + frame"
```

---

## Task S4: Backlog-pull endpoint `GET /v1/messages`

**Files:**
- Create: `server/src/main/java/com/cloak/server/domain/message/MessageCursor.java`
- Create: `server/src/main/java/com/cloak/server/port/output/message/MessageHistoryQueryPort.java`
- Create: `server/src/main/java/com/cloak/server/adapter/output/database/message/MessageHistoryQueryAdapter.java`
- Create: `server/src/main/java/com/cloak/server/usecase/FetchMessageHistoryUseCase.java`
- Create: `server/src/main/java/com/cloak/server/adapter/input/rest/messages/MessageHistoryController.java`
- Create: `server/src/main/java/com/cloak/server/adapter/input/rest/messages/MessageHistoryResponse.java`
- Test: `server/src/test/java/com/cloak/server/adapter/input/rest/messages/MessageHistoryControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `encrypted_message` columns from S1; `WrappedResponse` (`com.cloak.server.common.web`).
- Produces: `GET /v1/messages?cursor=<opaque>&limit=<n>` → `WrappedResponse<MessageHistoryResponse>` where `MessageHistoryResponse(List<Item> messages, boolean hasMore)` and `Item(messageId, fromSub, fromDeviceId, toDeviceId, messageType, conversationId, ciphertextBase64, cursor)`.
- `MessageCursor.encode(Instant createdAt, UUID id) -> String` (base64 of `"<epochMicros>:<uuid>"`); `MessageCursor.decode(String) -> MessageCursor(Instant, UUID)`; `null`/blank cursor means "from the beginning".

- [ ] **Step 1: Write the failing controller integration test** (`@SpringBootTest`, Testcontainers, authenticated as bob — follow the Slice 2 REST test for JWT setup). Seed three messages addressed to bob (via the repository or WS ingest), then:
```java
@Test
void paginatesBacklogInOrderWithKeysetCursor() {
  // given: 3 messages persisted for bob (m1,m2,m3 in created order)
  var page1 = getHistory(bobToken, null, 2);           // limit 2
  assertThat(page1.messages()).hasSize(2);
  assertThat(page1.hasMore()).isTrue();
  assertThat(page1.messages().get(0).messageId()).isEqualTo(m1);
  var page2 = getHistory(bobToken, page1.messages().get(1).cursor(), 2);
  assertThat(page2.messages()).hasSize(1);
  assertThat(page2.hasMore()).isFalse();
  assertThat(page2.messages().get(0).messageId()).isEqualTo(m3);
}

@Test
void returnsOnlyMessagesAddressedToCaller() {
  // a message addressed to carol must NOT appear in bob's history
}
```

- [ ] **Step 2: Run — expect FAIL** (404 / no endpoint).

- [ ] **Step 3: Implement `MessageCursor`** (opaque codec):
```java
package com.cloak.server.domain.message;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Opaque keyset cursor over {@code (created_at, id)}. Encoded so clients don't couple to columns. */
public record MessageCursor(Instant createdAt, UUID id) {
  public String encode() {
    long micros = createdAt.getEpochSecond() * 1_000_000L + createdAt.getNano() / 1_000L;
    String raw = micros + ":" + id;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /** Decodes a cursor token, or {@code null} for a blank/absent cursor (start from the beginning). */
  public static MessageCursor decode(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    try {
      String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
      int sep = raw.lastIndexOf(':');
      long micros = Long.parseLong(raw.substring(0, sep));
      Instant ts = Instant.ofEpochSecond(micros / 1_000_000L, (micros % 1_000_000L) * 1_000L);
      return new MessageCursor(ts, UUID.fromString(raw.substring(sep + 1)));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("malformed cursor");
    }
  }
}
```

- [ ] **Step 4: Define the query port + read model:**
```java
package com.cloak.server.port.output.message;

import com.cloak.server.domain.message.MessageCursor;
import java.util.List;

public interface MessageHistoryQueryPort {
  /** Messages addressed to {@code recipientSub} after {@code cursor} (null = from start), ORDER BY (created_at,id). */
  List<HistoryRow> page(String recipientSub, MessageCursor cursor, int limit);

  record HistoryRow(String messageId, String fromSub, int fromDeviceId, int toDeviceId,
      int messageType, String conversationId, byte[] ciphertext, MessageCursor cursor) {}
}
```

- [ ] **Step 5: Implement `MessageHistoryQueryAdapter`** (JdbcClient, keyset — mirror `PreKeyBundleQueryAdapter`'s style):
```java
@Repository
class MessageHistoryQueryAdapter implements MessageHistoryQueryPort {
  private final JdbcClient jdbc;
  MessageHistoryQueryAdapter(JdbcClient jdbc) { this.jdbc = jdbc; }

  private static final String FROM_START = """
      SELECT id, sender_sub, recipient_sub, conversation_id, sender_device_id, recipient_device_id,
             message_type, ciphertext, created_at
      FROM encrypted_message
      WHERE recipient_sub = :me
      ORDER BY created_at, id LIMIT :limit""";

  private static final String AFTER_CURSOR = """
      SELECT id, sender_sub, recipient_sub, conversation_id, sender_device_id, recipient_device_id,
             message_type, ciphertext, created_at
      FROM encrypted_message
      WHERE recipient_sub = :me AND (created_at, id) > (:ts, :id)
      ORDER BY created_at, id LIMIT :limit""";

  @Override
  public List<HistoryRow> page(String recipientSub, MessageCursor cursor, int limit) {
    var spec = (cursor == null)
        ? jdbc.sql(FROM_START).param("me", recipientSub).param("limit", limit)
        : jdbc.sql(AFTER_CURSOR).param("me", recipientSub).param("limit", limit)
            .param("ts", OffsetDateTime.ofInstant(cursor.createdAt(), ZoneOffset.UTC))
            .param("id", cursor.id());
    return spec.query((rs, n) -> {
      UUID id = rs.getObject("id", UUID.class);
      Instant createdAt = rs.getObject("created_at", OffsetDateTime.class).toInstant();
      return new HistoryRow(id.toString(), rs.getString("sender_sub"), rs.getInt("sender_device_id"),
          rs.getInt("recipient_device_id"), rs.getInt("message_type"), rs.getString("conversation_id"),
          rs.getBytes("ciphertext"), new MessageCursor(createdAt, id));
    }).list();
  }
}
```

- [ ] **Step 6: Implement `FetchMessageHistoryUseCase`** (`@Observed(name = "cloak.messages.history")`), capping `limit`:
```java
@Component
public class FetchMessageHistoryUseCase {
  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 200;
  private final MessageHistoryQueryPort query;
  FetchMessageHistoryUseCase(MessageHistoryQueryPort query) { this.query = query; }

  @Observed(name = "cloak.messages.history")
  public Page fetch(String recipientSub, String cursorToken, Integer limit) {
    int capped = Math.min(limit == null || limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT);
    var rows = query.page(recipientSub, MessageCursor.decode(cursorToken), capped);
    return new Page(rows, rows.size() == capped);
  }
  public record Page(java.util.List<MessageHistoryQueryPort.HistoryRow> rows, boolean hasMore) {}
}
```

- [ ] **Step 7: Implement the response DTO + controller.** `MessageHistoryResponse` base64-encodes ciphertext + surfaces each row's `cursor`:
```java
@RestController
public class MessageHistoryController {
  private final FetchMessageHistoryUseCase useCase;
  MessageHistoryController(FetchMessageHistoryUseCase useCase) { this.useCase = useCase; }

  @GetMapping("/v1/messages")
  WrappedResponse<MessageHistoryResponse> history(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    var page = useCase.fetch(jwt.getSubject(), cursor, limit);
    return WrappedResponse.ok(MessageHistoryResponse.from(page));
  }
}
```
`MessageHistoryResponse.from(page)` maps each `HistoryRow` to an item with `Base64.getEncoder().encodeToString(row.ciphertext())` and `row.cursor().encode()`, and sets `hasMore`.

- [ ] **Step 8: Run the test — expect PASS.** Then `./gradlew checkstyleMain` (Javadoc on public controller/DTO/use-case members). A malformed cursor throws `IllegalArgumentException` → 400 via the existing `GlobalExceptionHandler` — add a test asserting `cursor=not-base64` yields 400.

- [ ] **Step 9: Full server gate.**
```bash
cd server && ./gradlew check
```
Expected: BUILD SUCCESSFUL, coverage ≥ 0.90.

- [ ] **Step 10: Commit**
```bash
git add server/src
git commit -m "feat(server): stateless keyset-paginated GET /v1/messages backlog endpoint"
```

---

## Task C1: ConversationKey (iOS)

**Files:**
- Create: `app/Cloak/Foundation/Encryption/ConversationKey.swift`
- Test: `app/CloakTests/Foundation/Encryption/ConversationKeyTests.swift`

**Interfaces:**
- Produces: `enum ConversationKey { static func of(_ subs: [String]) -> String }` — lowercase sha256 hex of sorted, `:`-joined subs. Must equal the server's `ConversationId.canonical`.

- [ ] **Step 1: Write the failing test** (same vector as the fixture):
```swift
import Testing
@testable import Cloak

struct ConversationKeyTests {
    @Test func matchesCanonicalSha256AndIsOrderIndependent() throws {
        let a = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        let b = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        let expected = "<PASTE hex from Task A1 Step 2>"
        #expect(ConversationKey.of([a, b]) == expected)
        #expect(ConversationKey.of([b, a]) == expected)
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (type missing).

- [ ] **Step 3: Implement:**
```swift
import Crypto
import Foundation

/// Deterministic canonical conversation id shared with the server (`ConversationId.canonical`):
/// lowercase SHA-256 hex of the sorted, ':'-joined member subs. For 1:1, `subs = [mySub, peerSub]`.
enum ConversationKey {
    static func of(_ subs: [String]) -> String {
        let joined = subs.sorted().joined(separator: ":")
        let digest = SHA256.hash(data: Data(joined.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}
```
> Uses swift-crypto's `Crypto` (already transitively available via libsignal) — if the module isn't resolvable, use `import CryptoKit` and `SHA256.hash`. Prefer whichever the project already links; check an existing crypto import first.

- [ ] **Step 4: Run — expect PASS.** Run `swiftlint --strict` on the new file.

- [ ] **Step 5: Commit**
```bash
git add app/Cloak/Foundation/Encryption/ConversationKey.swift app/CloakTests/Foundation/Encryption/ConversationKeyTests.swift
git commit -m "feat(ios): ConversationKey — canonical conversation id matching the server"
```

---

## Task C2: MessageContent inner payload

**Files:**
- Create: `app/Cloak/Foundation/Encryption/MessageContent.swift`
- Test: `app/CloakTests/Foundation/Encryption/MessageContentTests.swift`

**Interfaces:**
- Produces: `struct MessageContent: Codable, Equatable, Sendable { let sentAt: Date; let text: String }` with `func serialized() throws -> Data` and `static func deserialize(_ data: Data) throws -> MessageContent`, using `millisecondsSince1970` date coding (stable, locale-independent).

- [ ] **Step 1: Write the failing round-trip test:**
```swift
import Foundation
import Testing
@testable import Cloak

struct MessageContentTests {
    @Test func roundTripsThroughSerialization() throws {
        let original = MessageContent(sentAt: Date(timeIntervalSince1970: 1_700_000.123), text: "hi 🔒")
        let restored = try MessageContent.deserialize(try original.serialized())
        #expect(restored.text == "hi 🔒")
        #expect(abs(restored.sentAt.timeIntervalSince1970 - original.sentAt.timeIntervalSince1970) < 0.001)
    }
}
```

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Implement:**
```swift
import Foundation

/// The sender-authoritative message payload encrypted as the Signal plaintext (spec: "decrypt once"
/// stores this locally). `sentAt` drives display order — it never travels as cleartext metadata.
struct MessageContent: Codable, Equatable, Sendable {
    let sentAt: Date
    let text: String

    private static func encoder() -> JSONEncoder {
        let e = JSONEncoder(); e.dateEncodingStrategy = .millisecondsSince1970; return e
    }
    private static func decoder() -> JSONDecoder {
        let d = JSONDecoder(); d.dateDecodingStrategy = .millisecondsSince1970; return d
    }

    func serialized() throws -> Data { try Self.encoder().encode(self) }
    static func deserialize(_ data: Data) throws -> MessageContent {
        try decoder().decode(MessageContent.self, from: data)
    }
}
```

- [ ] **Step 4: Run — expect PASS**; `swiftlint --strict`.

- [ ] **Step 5: Commit**
```bash
git add app/Cloak/Foundation/Encryption/MessageContent.swift app/CloakTests/Foundation/Encryption/MessageContentTests.swift
git commit -m "feat(ios): MessageContent inner payload (sentAt + text)"
```

---

## Task C3: MessageEnvelope gains conversationId

**Files:**
- Modify: `app/Cloak/Foundation/Network/MessageTransport.swift`
- Test: `app/CloakTests/Foundation/Network/MessageEnvelopeTests.swift` (existing — extend)

**Interfaces:**
- Produces: `MessageEnvelope` gains `let conversationId: String` (init param after `messageType`); encodes/decodes per the slice3 fixture.

- [ ] **Step 1: Write the failing test** — decode `slice3-message-envelope.json` and assert `conversationId`:
```swift
@Test func decodesConversationIdFromSlice3Fixture() throws {
    let data = try #require(fixtureData("slice3-message-envelope"))
    let env = try #require(MessageEnvelope.decode(text: #require(String(data: data, encoding: .utf8))))
    #expect(!env.conversationId.isEmpty)
    #expect(env.fromSub == "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
}
```
(Use whatever fixture-loading helper the existing envelope test uses.)

- [ ] **Step 2: Run — expect FAIL** (no `conversationId`).

- [ ] **Step 3: Add the field.** In `MessageEnvelope`, add `let conversationId: String` after `messageType`, add it to the memberwise `init` (after `messageType`, before `ciphertext`), and set it in the assignment block. Update the doc comment.

- [ ] **Step 4: Run — expect PASS**; `swiftlint --strict`.

- [ ] **Step 5: Commit**
```bash
git add app/Cloak/Foundation/Network/MessageTransport.swift app/CloakTests/Foundation/Network/MessageEnvelopeTests.swift
git commit -m "feat(ios): MessageEnvelope carries conversationId"
```

---

## Task C4: Local encrypted message history (GRDB)

**Files:**
- Create: `app/Cloak/Foundation/Persistence/MessageRecord.swift`
- Create: `app/Cloak/Foundation/Persistence/GRDBMessageRepository.swift`
- Test: `app/CloakTests/Foundation/Persistence/GRDBMessageRepositoryTests.swift`

**Interfaces:**
- Produces:
  - `enum MessageStatus: String, Codable, Sendable { case sending, sent, failed }`
  - `struct StoredMessage: Equatable, Sendable { let messageId, conversationId, peerSub: String; let isMine: Bool; let sentAt: Date; let body: String; var status: MessageStatus }`
  - `protocol MessageRepository: Sendable { func save(_:) throws; func updateStatus(messageId:String, status:MessageStatus) throws; func loadThread(conversationId:String) throws -> [StoredMessage]; func latestPerConversation() throws -> [StoredMessage] }`
  - `struct GRDBMessageRepository: MessageRepository { init(database: DatabaseQueue) throws }` — creates the table; `save` is upsert-do-nothing on `message_id` (idempotent for backlog/live dedupe).

- [ ] **Step 1: Write failing tests** (temp on-disk SQLCipher DB via `EncryptedDatabase.open` with a temp path + test passphrase):
```swift
import Foundation
import GRDB
import Testing
@testable import Cloak

struct GRDBMessageRepositoryTests {
    private func makeRepo() throws -> GRDBMessageRepository {
        let path = NSTemporaryDirectory() + UUID().uuidString + ".sqlite"
        let db = try EncryptedDatabase.open(path: path, passphrase: "test-pass")
        return try GRDBMessageRepository(database: db)
    }

    @Test func savesAndLoadsThreadInSentAtOrder() throws {
        let repo = try makeRepo()
        let convo = "c1"
        try repo.save(StoredMessage(messageId: "m2", conversationId: convo, peerSub: "bob",
            isMine: false, sentAt: Date(timeIntervalSince1970: 200), body: "second", status: .sent))
        try repo.save(StoredMessage(messageId: "m1", conversationId: convo, peerSub: "bob",
            isMine: true, sentAt: Date(timeIntervalSince1970: 100), body: "first", status: .sent))
        let thread = try repo.loadThread(conversationId: convo)
        #expect(thread.map(\.messageId) == ["m1", "m2"])
    }

    @Test func saveIsIdempotentOnMessageId() throws {
        let repo = try makeRepo()
        let msg = StoredMessage(messageId: "dup", conversationId: "c1", peerSub: "bob",
            isMine: false, sentAt: Date(timeIntervalSince1970: 1), body: "x", status: .sent)
        try repo.save(msg); try repo.save(msg)
        #expect(try repo.loadThread(conversationId: "c1").count == 1)
    }

    @Test func updateStatusChangesStoredStatus() throws {
        let repo = try makeRepo()
        try repo.save(StoredMessage(messageId: "m1", conversationId: "c1", peerSub: "bob",
            isMine: true, sentAt: Date(timeIntervalSince1970: 1), body: "x", status: .sending))
        try repo.updateStatus(messageId: "m1", status: .sent)
        #expect(try repo.loadThread(conversationId: "c1").first?.status == .sent)
    }

    @Test func latestPerConversationReturnsNewestEach() throws {
        let repo = try makeRepo()
        try repo.save(StoredMessage(messageId: "a1", conversationId: "cA", peerSub: "bob",
            isMine: true, sentAt: Date(timeIntervalSince1970: 10), body: "old", status: .sent))
        try repo.save(StoredMessage(messageId: "a2", conversationId: "cA", peerSub: "bob",
            isMine: false, sentAt: Date(timeIntervalSince1970: 20), body: "new", status: .sent))
        try repo.save(StoredMessage(messageId: "b1", conversationId: "cB", peerSub: "carol",
            isMine: true, sentAt: Date(timeIntervalSince1970: 15), body: "hey", status: .sent))
        let latest = try repo.latestPerConversation()
        #expect(latest.count == 2)
        #expect(latest.first(where: { $0.conversationId == "cA" })?.body == "new")
    }
}
```

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Implement `MessageRecord.swift`** (types only):
```swift
import Foundation

enum MessageStatus: String, Codable, Sendable { case sending, sent, failed }

/// A message in the local encrypted history (plaintext `body`, protected at rest by SQLCipher).
struct StoredMessage: Equatable, Sendable {
    let messageId: String
    let conversationId: String
    let peerSub: String
    let isMine: Bool
    let sentAt: Date
    let body: String
    var status: MessageStatus
}

protocol MessageRepository: Sendable {
    func save(_ message: StoredMessage) throws
    func updateStatus(messageId: String, status: MessageStatus) throws
    func loadThread(conversationId: String) throws -> [StoredMessage]
    func latestPerConversation() throws -> [StoredMessage]
}
```

- [ ] **Step 4: Implement `GRDBMessageRepository.swift`** (raw SQL, matching `GRDBRegistrationState` style; `sent_at` stored as epoch double):
```swift
import Foundation
import GRDB

struct GRDBMessageRepository: MessageRepository {
    private let database: DatabaseQueue

    init(database: DatabaseQueue) throws {
        self.database = database
        try database.write { conn in
            try conn.execute(sql: """
                CREATE TABLE IF NOT EXISTS message(
                    message_id      TEXT PRIMARY KEY,
                    conversation_id TEXT    NOT NULL,
                    peer_sub        TEXT    NOT NULL,
                    is_mine         INTEGER NOT NULL,
                    sent_at         DOUBLE  NOT NULL,
                    body            TEXT    NOT NULL,
                    status          TEXT    NOT NULL
                )
                """)
            try conn.execute(sql:
                "CREATE INDEX IF NOT EXISTS idx_message_thread ON message(conversation_id, sent_at)")
        }
    }

    func save(_ m: StoredMessage) throws {
        try database.write { conn in
            try conn.execute(sql: """
                INSERT INTO message(message_id, conversation_id, peer_sub, is_mine, sent_at, body, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(message_id) DO NOTHING
                """, arguments: [m.messageId, m.conversationId, m.peerSub, m.isMine,
                                 m.sentAt.timeIntervalSince1970, m.body, m.status.rawValue])
        }
    }

    func updateStatus(messageId: String, status: MessageStatus) throws {
        try database.write { conn in
            try conn.execute(sql: "UPDATE message SET status = ? WHERE message_id = ?",
                             arguments: [status.rawValue, messageId])
        }
    }

    func loadThread(conversationId: String) throws -> [StoredMessage] {
        try database.read { conn in
            try Row.fetchAll(conn, sql:
                "SELECT * FROM message WHERE conversation_id = ? ORDER BY sent_at, message_id",
                arguments: [conversationId]).map(Self.decode)
        }
    }

    func latestPerConversation() throws -> [StoredMessage] {
        try database.read { conn in
            try Row.fetchAll(conn, sql: """
                SELECT m.* FROM message m
                JOIN (SELECT conversation_id, MAX(sent_at) AS mx FROM message GROUP BY conversation_id) t
                  ON m.conversation_id = t.conversation_id AND m.sent_at = t.mx
                ORDER BY m.sent_at DESC
                """).map(Self.decode)
        }
    }

    private static func decode(_ row: Row) -> StoredMessage {
        StoredMessage(
            messageId: row["message_id"], conversationId: row["conversation_id"],
            peerSub: row["peer_sub"], isMine: (row["is_mine"] as Int64) != 0,
            sentAt: Date(timeIntervalSince1970: row["sent_at"]), body: row["body"],
            status: MessageStatus(rawValue: row["status"]) ?? .sent)
    }
}
```

- [ ] **Step 5: Run — expect PASS**; `swiftlint --strict`.

- [ ] **Step 6: Commit**
```bash
git add app/Cloak/Foundation/Persistence/MessageRecord.swift app/Cloak/Foundation/Persistence/GRDBMessageRepository.swift app/CloakTests/Foundation/Persistence/GRDBMessageRepositoryTests.swift
git commit -m "feat(ios): local encrypted message history (GRDB MessageRepository)"
```

---

## Task C5: Backlog cursor store (GRDB)

**Files:**
- Create: `app/Cloak/Foundation/Persistence/GRDBBacklogCursor.swift`
- Test: `app/CloakTests/Foundation/Persistence/GRDBBacklogCursorTests.swift`

**Interfaces:**
- Produces: `protocol BacklogCursorStore: Sendable { func highWaterMark() -> String?; func advance(to cursor: String) }` and `struct GRDBBacklogCursor: BacklogCursorStore { init(database: DatabaseQueue) throws }` — single-row table (`CHECK (id = 1)`), fail-safe reads (nil on error, so a transient failure re-pulls rather than skips).

- [ ] **Step 1: Write failing test:**
```swift
@Test func startsNilThenPersistsAdvances() throws {
    let path = NSTemporaryDirectory() + UUID().uuidString + ".sqlite"
    let db = try EncryptedDatabase.open(path: path, passphrase: "test-pass")
    let store = try GRDBBacklogCursor(database: db)
    #expect(store.highWaterMark() == nil)
    store.advance(to: "cursor-1")
    #expect(store.highWaterMark() == "cursor-1")
}
```

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Implement** (mirror `GRDBRegistrationState`):
```swift
import Foundation
import GRDB

protocol BacklogCursorStore: Sendable {
    func highWaterMark() -> String?
    func advance(to cursor: String)
}

/// Single-row store of the last successfully-consumed backlog cursor. A read failure returns nil so
/// the next sync re-pulls from the previous mark rather than skipping messages (fail-safe).
struct GRDBBacklogCursor: BacklogCursorStore {
    private let database: DatabaseQueue
    init(database: DatabaseQueue) throws {
        self.database = database
        try database.write { conn in
            try conn.execute(sql: """
                CREATE TABLE IF NOT EXISTS backlog_cursor(
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    cursor TEXT NOT NULL
                )
                """)
        }
    }
    func highWaterMark() -> String? {
        (try? database.read { conn in
            try String.fetchOne(conn, sql: "SELECT cursor FROM backlog_cursor WHERE id = 1")
        }) ?? nil
    }
    func advance(to cursor: String) {
        try? database.write { conn in
            try conn.execute(sql: """
                INSERT INTO backlog_cursor(id, cursor) VALUES (1, ?)
                ON CONFLICT(id) DO UPDATE SET cursor = excluded.cursor
                """, arguments: [cursor])
        }
    }
}
```

- [ ] **Step 4: Run — expect PASS**; `swiftlint --strict`.

- [ ] **Step 5: Commit**
```bash
git add app/Cloak/Foundation/Persistence/GRDBBacklogCursor.swift app/CloakTests/Foundation/Persistence/GRDBBacklogCursorTests.swift
git commit -m "feat(ios): backlog high-water-mark cursor store"
```

---

## Task C6: MessageHistoryClient (REST GET)

**Files:**
- Create: `app/Cloak/Feature/Conversation/MessageHistoryClient.swift`
- Test: `app/CloakTests/Feature/Conversation/MessageHistoryClientTests.swift`

**Interfaces:**
- Consumes: `HTTPGetRunner` (existing, in `PreKeyBundleClient.swift`).
- Produces:
  - `struct BacklogMessage: Codable, Equatable, Sendable { let messageId, fromSub: String; let fromDeviceId, toDeviceId: UInt32; let messageType: SignalMessageType; let conversationId, ciphertext, cursor: String }`
  - `struct BacklogPage: Codable, Equatable, Sendable { let messages: [BacklogMessage]; let hasMore: Bool }`
  - `protocol MessageHistoryClient: Sendable { func fetchHistory(cursor: String?, limit: Int, accessToken: String) async throws -> BacklogPage }`
  - `struct HTTPMessageHistoryClient: MessageHistoryClient { let baseURL: URL; let runner: HTTPGetRunner }` → `GET /v1/messages?cursor=&limit=`, unwraps `data`.

- [ ] **Step 1: Write the failing test** using a stub `HTTPGetRunner` returning `slice3-message-history.json`:
```swift
@Test func decodesBacklogPageFromFixture() async throws {
    let data = try #require(fixtureData("slice3-message-history"))
    let client = HTTPMessageHistoryClient(baseURL: #require(URL(string: "http://localhost")),
                                          runner: StubRunner(response: data))
    let page = try await client.fetchHistory(cursor: nil, limit: 50, accessToken: "t")
    #expect(page.hasMore == false)
    #expect(page.messages.first?.messageType == .preKey)
    #expect(page.messages.first?.cursor.isEmpty == false)
}
```
(`StubRunner` conforms to `HTTPGetRunner` and returns the fixture bytes — mirror the stub used in `PreKeyBundleClient` tests.)

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Implement:**
```swift
import Foundation

struct BacklogMessage: Codable, Equatable, Sendable {
    let messageId: String
    let fromSub: String
    let fromDeviceId: UInt32
    let toDeviceId: UInt32
    let messageType: SignalMessageType
    let conversationId: String
    let ciphertext: String
    let cursor: String
}

struct BacklogPage: Codable, Equatable, Sendable {
    let messages: [BacklogMessage]
    let hasMore: Bool
}

protocol MessageHistoryClient: Sendable {
    func fetchHistory(cursor: String?, limit: Int, accessToken: String) async throws -> BacklogPage
}

/// Calls `GET /v1/messages?cursor=&limit=` with a bearer token and unwraps the `{ "data": … }` envelope.
struct HTTPMessageHistoryClient: MessageHistoryClient {
    let baseURL: URL
    let runner: HTTPGetRunner

    func fetchHistory(cursor: String?, limit: Int, accessToken: String) async throws -> BacklogPage {
        let base = baseURL.appendingPathComponent("v1/messages")
        guard var components = URLComponents(url: base, resolvingAgainstBaseURL: false) else {
            preconditionFailure("baseURL must be a valid URL")
        }
        var items = [URLQueryItem(name: "limit", value: String(limit))]
        if let cursor { items.append(URLQueryItem(name: "cursor", value: cursor)) }
        components.queryItems = items
        guard let url = components.url else { preconditionFailure("URLComponents must produce a URL") }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        let data = try await runner.get(request)
        return try JSONDecoder().decode(HistoryEnvelope.self, from: data).data
    }

    private struct HistoryEnvelope: Decodable { let data: BacklogPage }
}
```

- [ ] **Step 4: Run — expect PASS**; `swiftlint --strict`.

- [ ] **Step 5: Commit**
```bash
git add app/Cloak/Feature/Conversation/MessageHistoryClient.swift app/CloakTests/Feature/Conversation/MessageHistoryClientTests.swift
git commit -m "feat(ios): MessageHistoryClient — GET /v1/messages backlog pull"
```

---

## Task C7: BacklogSync

**Files:**
- Create: `app/Cloak/Feature/Conversation/BacklogSync.swift`
- Test: `app/CloakTests/Feature/Conversation/BacklogSyncTests.swift`

**Interfaces:**
- Consumes: `MessageHistoryClient`, `MessageCrypting`, `MessageRepository`, `BacklogCursorStore`.
- Produces: `struct BacklogSync: Sendable { init(client:crypto:repository:cursor:tokenProvider:); func sync() async }` — pulls pages from the high-water mark; per message: decrypt-once → deserialize `MessageContent` → `repository.save(StoredMessage(isMine:false, peerSub: fromSub, conversationId:, sentAt:, body:, status: .sent))` → `cursor.advance(to: msg.cursor)`. On decrypt/decode failure: log + **stop** (don't advance past the bad message; it retries next sync). Continues while `hasMore`.

- [ ] **Step 1: Write failing tests** with a mock client (canned pages), a mock crypto (returns a serialized `MessageContent`), a real temp-DB repository, and a real temp-DB cursor:
```swift
@Test func decryptsPersistsAndAdvancesCursor() async throws {
    let content = try MessageContent(sentAt: Date(timeIntervalSince1970: 5), text: "hello").serialized()
    let msg = BacklogMessage(messageId: "m1", fromSub: "bob", fromDeviceId: 1, toDeviceId: 1,
        messageType: .normal, conversationId: "c1", ciphertext: Data("ct".utf8).base64EncodedString(),
        cursor: "cur-1")
    let sync = BacklogSync(
        client: MockHistoryClient(pages: [BacklogPage(messages: [msg], hasMore: false)]),
        crypto: MockCrypto(decryptResult: content),
        repository: repo, cursor: cursorStore, tokenProvider: { "t" })
    await sync.sync()
    #expect(try repo.loadThread(conversationId: "c1").first?.body == "hello")
    #expect(cursorStore.highWaterMark() == "cur-1")
}

@Test func stopsAndDoesNotAdvancePastAFailedDecrypt() async throws {
    let good = /* BacklogMessage m1, cursor "cur-1" */
    let bad  = /* BacklogMessage m2, cursor "cur-2" */
    let sync = BacklogSync(client: MockHistoryClient(pages: [BacklogPage(messages: [good, bad], hasMore: false)]),
        crypto: MockCrypto(failOnCiphertext: bad.ciphertext), repository: repo, cursor: cursorStore,
        tokenProvider: { "t" })
    await sync.sync()
    #expect(cursorStore.highWaterMark() == "cur-1")   // advanced past good, not bad
    #expect(try repo.loadThread(conversationId: good.conversationId).count == 1)
}
```

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Implement:**
```swift
import Foundation
import OSLog

/// Pulls not-yet-decrypted ciphertext addressed to this device, decrypts-once, and persists it to
/// local history (spec: decrypt-once → local plaintext is authoritative). Idempotent (repository
/// dedups on messageId); advances the high-water mark only past successfully-consumed messages.
struct BacklogSync: Sendable {
    let client: MessageHistoryClient
    let crypto: MessageCrypting
    let repository: MessageRepository
    let cursor: BacklogCursorStore
    let tokenProvider: @Sendable () async throws -> String
    private let log = Logger(subsystem: "com.cloak.app", category: "backlog")

    func sync() async {
        do {
            let token = try await tokenProvider()
            var hasMore = true
            while hasMore {
                let page = try await client.fetchHistory(
                    cursor: cursor.highWaterMark(), limit: 50, accessToken: token)
                for msg in page.messages {
                    guard let ciphertext = Data(base64Encoded: msg.ciphertext) else {
                        log.error("Dropping backlog message with invalid base64 ciphertext"); return
                    }
                    do {
                        let plaintext = try crypto.decrypt(ciphertext, type: msg.messageType,
                            from: msg.fromSub, deviceId: msg.fromDeviceId)
                        let content = try MessageContent.deserialize(plaintext)
                        try repository.save(StoredMessage(
                            messageId: msg.messageId, conversationId: msg.conversationId,
                            peerSub: msg.fromSub, isMine: false, sentAt: content.sentAt,
                            body: content.text, status: .sent))
                        cursor.advance(to: msg.cursor)
                    } catch {
                        // Stop without advancing past this message so it retries next sync.
                        log.error("Backlog decrypt/persist failed; will retry next sync")
                        return
                    }
                }
                hasMore = page.hasMore
            }
        } catch {
            log.error("Backlog sync aborted: \(error.localizedDescription, privacy: .public)")
        }
    }
}
```

- [ ] **Step 4: Run — expect PASS**; `swiftlint --strict`.

- [ ] **Step 5: Commit**
```bash
git add app/Cloak/Feature/Conversation/BacklogSync.swift app/CloakTests/Feature/Conversation/BacklogSyncTests.swift
git commit -m "feat(ios): BacklogSync — decrypt-once + persist not-yet-received messages"
```

---

## Task C8: ChatThreadViewModel — persist, restore, sort

**Files:**
- Modify: `app/Cloak/Feature/Conversation/ChatThreadViewModel.swift`
- Test: `app/CloakTests/Feature/Conversation/ChatThreadViewModelTests.swift` (existing — extend)

**Interfaces:**
- Consumes: `MessageRepository`, `ConversationKey`, `MessageContent`.
- Produces: `ChatBubble` gains `let sentAt: Date` + `var status: MessageStatus`. `ChatThreadViewModel.init` gains `repository: MessageRepository`, `mySub: String`. New `func loadHistory()`; `send`/`receive` persist + insert by `sentAt`.

- [ ] **Step 1: Write failing tests** (mock transport + mock crypto + temp-DB repository):
```swift
@Test func loadsExistingThreadFromRepositoryOnLoad() throws {
    try repo.save(StoredMessage(messageId: "m1", conversationId: convoId, peerSub: bobSub,
        isMine: false, sentAt: Date(timeIntervalSince1970: 1), body: "earlier", status: .sent))
    let vm = makeVM()             // injects repo, mySub, recipient=bob
    vm.loadHistory()
    #expect(vm.bubbles.map(\.text) == ["earlier"])
}

@Test func sendPersistsThenMarksSent() async throws {
    let vm = makeVM()
    await vm.send("hi")
    #expect(vm.bubbles.last?.text == "hi")
    #expect(vm.bubbles.last?.status == .sent)
    #expect(try repo.loadThread(conversationId: convoId).contains { $0.body == "hi" && $0.isMine })
}

@Test func lateArrivalInsertsInSentAtOrderNotAppended() async throws {
    let vm = makeVM()
    await vm.receive(envelope(id: "later", sentAt: 200, text: "B"))
    await vm.receive(envelope(id: "earlier", sentAt: 100, text: "A"))
    #expect(vm.bubbles.map(\.text) == ["A", "B"])   // inserted by sentAt, not arrival
}
```
(`envelope(id:sentAt:text:)` builds a `MessageEnvelope` whose ciphertext the mock crypto decrypts to a `MessageContent` with that `sentAt`/`text`; set `conversationId: convoId`, `fromSub: bobSub`.)

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Extend `ChatBubble`:**
```swift
struct ChatBubble: Identifiable, Equatable, Sendable {
    let id: String
    let text: String
    let isMine: Bool
    let sentAt: Date
    var status: MessageStatus
}
```

- [ ] **Step 4: Add dependencies + computed conversationId.** Add `private let repository: MessageRepository` and `private let mySub: String` to the stored properties and `init` (params after `myDeviceId`). Add:
```swift
private lazy var conversationId = ConversationKey.of([mySub, recipient.sub])

/// Inserts a bubble at its sentAt-sorted position (late arrivals slot in, not appended).
private func insert(_ bubble: ChatBubble) {
    let idx = bubbles.firstIndex { $0.sentAt > bubble.sentAt } ?? bubbles.endIndex
    bubbles.insert(bubble, at: idx)
}

/// Restores this thread from local encrypted history (call from the view's `.task`, before `start`).
func loadHistory() {
    bubbles = (try? repository.loadThread(conversationId: conversationId))?.map {
        ChatBubble(id: $0.messageId, text: $0.body, isMine: $0.isMine, sentAt: $0.sentAt, status: $0.status)
    } ?? []
}
```

- [ ] **Step 5: Rewrite `send`** to build `MessageContent`, persist(`sending`) → send → mark `sent`/`failed`, and set `conversationId` on the envelope:
```swift
func send(_ text: String) async {
    let messageId = UUID().uuidString
    let content = MessageContent(sentAt: Date(), text: text)
    do {
        try await ensureSession()
        let payload = try crypto.encrypt(try content.serialized(), to: recipient.sub, deviceId: recipient.deviceId)
        let stored = StoredMessage(messageId: messageId, conversationId: conversationId,
            peerSub: recipient.sub, isMine: true, sentAt: content.sentAt, body: text, status: .sending)
        try repository.save(stored)
        insert(ChatBubble(id: messageId, text: text, isMine: true, sentAt: content.sentAt, status: .sending))
        let envelope = MessageEnvelope(messageId: messageId, toSub: recipient.sub,
            toDeviceId: recipient.deviceId, fromDeviceId: myDeviceId, messageType: payload.type,
            conversationId: conversationId, ciphertext: payload.ciphertext.base64EncodedString())
        try await transport.send(envelope)
        try? repository.updateStatus(messageId: messageId, status: .sent)
        setStatus(messageId, .sent)
    } catch {
        try? repository.updateStatus(messageId: messageId, status: .failed)
        setStatus(messageId, .failed)
        self.error = error.localizedDescription
    }
}

private func setStatus(_ id: String, _ status: MessageStatus) {
    guard let i = bubbles.firstIndex(where: { $0.id == id }) else { return }
    bubbles[i].status = status
}
```

- [ ] **Step 6: Rewrite `receive`** to deserialize `MessageContent`, persist, and insert by `sentAt`:
```swift
func receive(_ env: MessageEnvelope) async {
    let senderSub = env.fromSub ?? recipient.sub
    guard senderSub == recipient.sub else { return }
    guard let ciphertextData = Data(base64Encoded: env.ciphertext) else {
        error = "Invalid base64 ciphertext in envelope \(env.messageId)"; return
    }
    do {
        let plaintext = try crypto.decrypt(ciphertextData, type: env.messageType,
            from: senderSub, deviceId: env.fromDeviceId)
        let content = try MessageContent.deserialize(plaintext)
        try repository.save(StoredMessage(messageId: env.messageId, conversationId: env.conversationId,
            peerSub: senderSub, isMine: false, sentAt: content.sentAt, body: content.text, status: .sent))
        insert(ChatBubble(id: env.messageId, text: content.text, isMine: false,
                          sentAt: content.sentAt, status: .sent))
    } catch {
        self.error = error.localizedDescription
    }
}
```
> `insert` dedups nothing itself; the repository `save` is idempotent, but guard the UI too: in `insert`, early-return if `bubbles.contains { $0.id == bubble.id }` so a live+backlog overlap can't double-render.

- [ ] **Step 7: Run — expect PASS**; `swiftlint --strict`.

- [ ] **Step 8: Commit**
```bash
git add app/Cloak/Feature/Conversation/ChatThreadViewModel.swift app/CloakTests/Feature/Conversation/ChatThreadViewModelTests.swift
git commit -m "feat(ios): persistent chat thread — restore, persist, sentAt ordering, status"
```

---

## Task C9: ConversationListViewModel + real list

**Files:**
- Create: `app/Cloak/Feature/Conversations/ConversationListViewModel.swift`
- Modify: `app/Cloak/Feature/Conversations/ConversationListView.swift`
- Test: `app/CloakTests/Feature/Conversations/ConversationListViewModelTests.swift`

**Interfaces:**
- Consumes: `MessageRepository`, `BacklogSync`.
- Produces:
  - `struct ConversationSummary: Identifiable, Equatable, Sendable { let conversationId, peerSub, lastMessage: String; let sentAt: Date; var id: String { conversationId } }`
  - `@MainActor @Observable final class ConversationListViewModel { private(set) var conversations: [ConversationSummary]; init(repository:backlogSync:); func load(); func refresh() async }` — `load()` reads `latestPerConversation()`; `refresh()` runs `backlogSync.sync()` then `load()`.

- [ ] **Step 1: Write failing tests** (temp-DB repository + a no-op/mock `BacklogSync` seam):
```swift
@MainActor @Test func loadMapsLatestPerConversation() throws {
    try repo.save(StoredMessage(messageId: "a2", conversationId: "cA", peerSub: "bob",
        isMine: false, sentAt: Date(timeIntervalSince1970: 20), body: "newest", status: .sent))
    let vm = ConversationListViewModel(repository: repo, backlogSync: NoopSync())
    vm.load()
    #expect(vm.conversations.first?.lastMessage == "newest")
    #expect(vm.conversations.first?.peerSub == "bob")
}
```
> To mock `BacklogSync`, extract a `protocol BacklogSyncing: Sendable { func sync() async }` in Task C7 (make `BacklogSync` conform) and depend on it here. If you prefer not to touch C7, inject a `@Sendable () async -> Void` closure instead. **Chosen approach: add the `BacklogSyncing` protocol** — update C7's `BacklogSync` to conform (one-line `: BacklogSyncing`) and this VM depends on `any BacklogSyncing`.

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Add the protocol to C7.** In `BacklogSync.swift` add `protocol BacklogSyncing: Sendable { func sync() async }` and make `struct BacklogSync: BacklogSyncing`.

- [ ] **Step 4: Implement the VM:**
```swift
import Foundation

struct ConversationSummary: Identifiable, Equatable, Sendable {
    let conversationId: String
    let peerSub: String
    let lastMessage: String
    let sentAt: Date
    var id: String { conversationId }
}

@MainActor @Observable final class ConversationListViewModel {
    private(set) var conversations: [ConversationSummary] = []
    private(set) var error: String?
    private let repository: MessageRepository
    private let backlogSync: any BacklogSyncing

    init(repository: MessageRepository, backlogSync: any BacklogSyncing) {
        self.repository = repository
        self.backlogSync = backlogSync
    }

    func load() {
        do {
            conversations = try repository.latestPerConversation().map {
                ConversationSummary(conversationId: $0.conversationId, peerSub: $0.peerSub,
                                    lastMessage: $0.body, sentAt: $0.sentAt)
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Pull any messages missed while away, then reload from local history.
    func refresh() async {
        await backlogSync.sync()
        load()
    }
}
```

- [ ] **Step 5: Wire the list view.** In `ConversationListView`, inject `repository`, `mySub`, and a `backlogSync` factory (see Task C10 for the exact composition-root call). Replace the empty-state `conversationList` body with a `List` over the VM's `conversations` that navigates to `.thread(ResolvedRecipient(sub: peerSub, deviceId: 1))`, keeping the empty-state view when `conversations.isEmpty`. Add `.task { vm.load(); await vm.refresh() }`. The `ChatThreadViewModel(...)` construction in `navigationDestination` gains `repository: repository, mySub: mySub`.

- [ ] **Step 6: Run VM tests — expect PASS**; `swiftlint --strict`. (The view itself is excluded from coverage, guide §14.5.)

- [ ] **Step 7: Commit**
```bash
git add app/Cloak/Feature/Conversations app/Cloak/Feature/Conversation/BacklogSync.swift app/CloakTests/Feature/Conversations
git commit -m "feat(ios): conversation list from local history + backlog refresh"
```

---

## Task C10: Composition-root wiring

**Files:**
- Modify: `app/Cloak/Feature/Conversation/RootView.swift`
- Modify: `app/Cloak/Feature/Conversations/ConversationListView.swift` (constructor params from C9)

**Interfaces:**
- Consumes: everything above. No new tests (pure UI wiring — verified by manual E2E, guide §14.5).

- [ ] **Step 1: Build the history graph in `RootView.body`.** Inside the `if let store …` branch, alongside `bundleClient`/`establisher`/`crypto`, add:
```swift
let repository = (try? GRDBMessageRepository(database: database))
let backlogCursor = (try? GRDBBacklogCursor(database: database))
let historyClient = HTTPMessageHistoryClient(baseURL: baseURL, runner: URLSessionHTTPGetRunner())
```
Guard-unwrap `repository` and `backlogCursor` (fall through to the error text if either is nil — the DB is already open, so this is defensive). Build:
```swift
let backlogSync = BacklogSync(client: historyClient, crypto: crypto, repository: repository,
    cursor: backlogCursor, tokenProvider: tokenProvider)
```

- [ ] **Step 2: Pass them into `ConversationListView`.** Add `repository: repository, mySub: mySub, backlogSync: backlogSync` to the `ConversationListView(...)` call, and thread `repository`/`mySub` into the `ChatThreadViewModel(...)` built in `ConversationListView`'s `navigationDestination` (from C9 Step 5). The `ConversationListViewModel` is created inside `ConversationListView` from the injected `repository` + `backlogSync`.

- [ ] **Step 3: Build the workspace + run the full suite.**
```bash
cd app && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodegen generate && pod install
# pre-boot the simulator, then:
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild test \
  -workspace app/Cloak.xcworkspace -scheme Cloak \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -test-timeouts-enabled YES -default-test-execution-time-allowance 60
```
Expected: all Swift Testing suites pass; coverage ≥ 90% on non-excluded targets.

- [ ] **Step 4: Commit**
```bash
git add app/Cloak/Feature
git commit -m "feat(ios): wire history repository + backlog sync into the composition root"
```

---

## Task D1: Docs, privacy gate, acceptance, squash

**Files:**
- Modify: root `README.md`, `server/README.md`, `db/README.md`, `app/README.md` (per-component rule).
- Modify: `docs/superpowers/specs/2026-07-29-slice-3-double-ratchet-history-design.md` (mark Status: Implemented).

- [ ] **Step 1: Update READMEs.** Root: note persistent 1:1 history + backlog endpoint. `server/README.md`: document `GET /v1/messages` (bearer, `cursor`/`limit`, keyset). `db/README.md`: V4 columns + the dropped `device_id`. `app/README.md`: local encrypted history + conversation list.

- [ ] **Step 2: Server privacy gate.** Run the privacy assertions and manually confirm no plaintext/`sentAt` in `encrypted_message` (only ciphertext + routing columns) and no ciphertext in logs:
```bash
cd server && ./gradlew check
```

- [ ] **Step 3: Two-simulator manual acceptance** (`./dev.sh up` + `./gradlew bootRun` + two sims):
  1. alice & bob each register/login.
  2. Multi-message back-and-forth in both directions (ratchet steps; forward secrecy).
  3. **Kill + relaunch both apps** → thread + conversation list restored from local DB (offline — before any network).
  4. alice sends while bob's thread is **closed** → bob opens the app → message arrives via backlog pull, in order.
  5. Inspect Postgres: `encrypted_message` holds ciphertext + routing columns only; no plaintext, no `sentAt`.
  6. Confirm out-of-order: (dev-only) delayed delivery still renders in `sentAt` order.

- [ ] **Step 4: Update the spec status line** to `Implemented (2026-07-29)`.

- [ ] **Step 5: Rebase + squash the branch to ONE commit** with a consolidated message (per root `CLAUDE.md` engineering workflow):
```bash
git fetch origin && git rebase origin/main
git reset --soft $(git merge-base HEAD origin/main)
git commit -m "feat: Slice 3 — Double Ratchet conversation + local encrypted history

Persistent 1:1 conversations with forward secrecy: local plaintext history
(SQLCipher) authoritative for relaunch/restore; stateless keyset-paginated
GET /v1/messages backlog for not-yet-received messages; deterministic
conversationId carried + validated; sender-authoritative sentAt (inside the
encrypted payload) drives display order + out-of-order insertion. Flyway V4
persists real device numbers / messageType / conversationId and retires the
MessageRowMapper sentinels + vestigial device_id UUID.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 6: Push + open PR** (do NOT merge — both `/code-review` and manual review are required per root `CLAUDE.md`):
```bash
git push -u origin feature/slice-3-double-ratchet-history
gh pr create --title "Slice 3 — Double Ratchet conversation + local history" --body "<summary + acceptance evidence>"
```

---

## Self-review

**Spec coverage** — every spec decision maps to a task:
- Local plaintext authoritative / relaunch restore → C4, C8 (loadHistory), C10, D1 acceptance.
- Stateless backlog endpoint + keyset `(created_at,id)` → S4; client consumer → C6, C7.
- Deterministic `conversationId` in envelope + validated → A1, S2 (ConversationId + validation), C1, C3.
- `conversationId` persisted + through Kafka → S1, S3.
- Sender `sentAt` inside encrypted payload; display order; out-of-order insert → C2, C8 (insert-by-sentAt).
- Cursor keyset, no global bigserial → S4 (MessageCursor), C5/C7 high-water mark.
- Status `sending/sent/failed` → C4 (MessageStatus), C8.
- Drop `device_id`, delete sentinels → S1.
- Privacy check → D1 Step 2/3; server privacy tests in S1/S4.
- Contract fixtures → A1.

**Placeholder scan:** the only intentional `<PASTE …>` tokens are the SHA-256 hex (computed in A1 Step 2 and reused in S2 Step 1 / C1 Step 1) and PR-body/README prose — all with explicit commands to produce them. No "add validation"/"handle edge cases"/"similar to Task N" placeholders.

**Type consistency:** `ConversationKey.of` (iOS) ≡ `ConversationId.canonical` (server) — same algorithm, verified against one shared fixture vector. `MessageEnvelope.conversationId` (C3) matches the inbound contract (A1) and the server `InboundEnvelope` (S2). `BacklogMessage.cursor` (C6) ≡ `MessageCursor.encode()` (S4). `StoredMessage`/`MessageStatus` used identically across C4/C7/C8/C9. `BacklogSyncing` protocol introduced in C7, consumed in C9/C10.
