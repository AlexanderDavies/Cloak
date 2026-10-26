package com.cloak.server.adapter.input.rest.messages;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloak.server.domain.message.Ciphertext;
import com.cloak.server.domain.message.ConversationId;
import com.cloak.server.domain.message.Message;
import com.cloak.server.domain.message.MessageId;
import com.cloak.server.port.output.message.MessageRepositoryPort;
import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Tokens;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Integration tests for {@code GET /v1/messages} — the stateless keyset-paginated backlog pull.
 * Covers: keyset ordering + cursor round-trip, recipient scoping (no cross-user leakage), and a
 * malformed cursor mapping to 400.
 */
class MessageHistoryControllerIntegrationTest extends IntegrationTestBase {

  @LocalServerPort int port;
  @Autowired MessageRepositoryPort repository;
  @Autowired JdbcTemplate jdbc;

  private static final ObjectMapper MAPPER =
      new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  private record HistoryItem(
      String messageId,
      String fromSub,
      int fromDeviceId,
      int toDeviceId,
      int messageType,
      String conversationId,
      String ciphertext,
      String cursor) {}

  private record HistoryPage(List<HistoryItem> messages, boolean hasMore) {}

  private record Envelope(HistoryPage data) {}

  private static void sleepPastClockTick() {
    try {
      Thread.sleep(5);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Persists one message addressed from {@code fromSub} to {@code toSub} and returns its id. */
  private String seedMessage(String fromSub, String toSub) {
    String id = UUID.randomUUID().toString();
    var convo = ConversationId.canonical(fromSub, toSub);
    repository.save(
        Message.create(
            new MessageId(id), fromSub, toSub, convo, 1, 1, 3, new Ciphertext(new byte[] {1, 2})));
    return id;
  }

  /**
   * Persists one message with an explicit {@code created_at}, bypassing the DB default so two calls
   * can share the exact same timestamp — the only way to reliably force a genuine keyset tie
   * ({@code MessageRepositoryPort.save} always lets Postgres assign {@code now()}).
   */
  private String seedMessageAt(String fromSub, String toSub, OffsetDateTime createdAt) {
    UUID id = UUID.randomUUID();
    String convo = ConversationId.canonical(fromSub, toSub);
    jdbc.update(
        """
        INSERT INTO encrypted_message
          (id, sender_sub, recipient_sub, conversation_id, sender_device_id, recipient_device_id,
           message_type, ciphertext, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        fromSub,
        toSub,
        convo,
        1,
        1,
        (short) 3,
        new byte[] {1, 2},
        createdAt);
    return id.toString();
  }

  /**
   * Walks to the tail of the caller's current backlog and returns its cursor (or {@code null} if
   * empty). The Postgres container backing this test class is shared across the whole
   * integration-test suite, so other test classes may already have routed messages to the real
   * seeded {@code bob}/{@code alice} accounts; anchoring on this tail keeps pagination assertions
   * below immune to that pre-existing state instead of assuming an empty inbox.
   */
  private String tailCursor(String token) {
    String cursor = null;
    HistoryPage page;
    do {
      page = getHistory(token, cursor, 200);
      if (!page.messages().isEmpty()) {
        cursor = page.messages().get(page.messages().size() - 1).cursor();
      }
    } while (page.hasMore());
    return cursor;
  }

  private HistoryPage getHistory(String token, String cursor, int limit) {
    String uri =
        cursor == null
            ? "/v1/messages?limit=" + limit
            : "/v1/messages?cursor=" + cursor + "&limit=" + limit;
    String body =
        client()
            .get()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    try {
      return MAPPER.readValue(body, Envelope.class).data();
    } catch (Exception e) {
      throw new IllegalStateException("failed to parse history response: " + body, e);
    }
  }

  @Test
  void paginatesBacklogInOrderWithKeysetCursor() {
    String bobToken = Tokens.accessToken(issuerUri(), "bob");
    String bobSub = Tokens.subject(bobToken);
    String aliceToken = Tokens.accessToken(issuerUri(), "alice");
    String aliceSub = Tokens.subject(aliceToken);
    String baseline = tailCursor(bobToken);

    // created_at is Postgres now() (transaction-start resolution); two back-to-back inserts can
    // land in the same microsecond. A short sleep keeps this test's insertion-order expectation
    // deterministic without touching the keyset SQL, which correctly falls back to `id` on ties.
    List<String> seeded = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      seeded.add(seedMessage(aliceSub, bobSub));
      sleepPastClockTick();
    }

    var page1 = getHistory(bobToken, baseline, 2);
    assertThat(page1.messages()).hasSize(2);
    assertThat(page1.hasMore()).isTrue();
    assertThat(page1.messages().get(0).messageId()).isEqualTo(seeded.get(0));
    assertThat(page1.messages().get(1).messageId()).isEqualTo(seeded.get(1));

    var page2 = getHistory(bobToken, page1.messages().get(1).cursor(), 2);
    assertThat(page2.messages()).hasSize(1);
    assertThat(page2.hasMore()).isFalse();
    assertThat(page2.messages().get(0).messageId()).isEqualTo(seeded.get(2));
  }

  /**
   * {@code created_at} is the inserting transaction's start time, not its commit time, so a row can
   * commit with a timestamp behind a cursor the client already holds — and a keyset pull would then
   * skip it forever. Rows still inside the settle window are therefore withheld until no
   * earlier-stamped row can still be committing. A row stamped an hour ahead stays unsettled under
   * any lag, so it must not be served.
   */
  @Test
  void withholdsRowsThatHaveNotYetSettled() {
    String bobToken = Tokens.accessToken(issuerUri(), "bob");
    String bobSub = Tokens.subject(bobToken);
    String aliceSub = Tokens.subject(Tokens.accessToken(issuerUri(), "alice"));
    String baseline = tailCursor(bobToken);

    String settled = seedMessageAt(aliceSub, bobSub, OffsetDateTime.now(ZoneOffset.UTC));
    String unsettled =
        seedMessageAt(aliceSub, bobSub, OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));

    var ids =
        getHistory(bobToken, baseline, 200).messages().stream()
            .map(HistoryItem::messageId)
            .toList();
    assertThat(ids).contains(settled).doesNotContain(unsettled);
  }

  /**
   * The compound {@code (created_at, id) > (:ts, :id)} comparison exists for exactly this case: two
   * rows sharing an identical {@code created_at}. A naive {@code created_at > :ts} predicate would
   * exclude the second tied row forever once the cursor advances past the first — neither row is
   * strictly greater than a cursor timestamp equal to both. Paging across the tie with {@code
   * limit=1} and requiring both ids to come back, exactly once between the two pages, catches that
   * degradation directly (verified by temporarily reverting the SQL to the naive predicate; this
   * test then fails with an empty second page instead of the tied row).
   */
  @Test
  void keysetPredicateReturnsBothTiedRowsExactlyOnceWhenPagingAcrossTheTie() {
    String bobToken = Tokens.accessToken(issuerUri(), "bob");
    String bobSub = Tokens.subject(bobToken);
    String aliceToken = Tokens.accessToken(issuerUri(), "alice");
    String aliceSub = Tokens.subject(aliceToken);
    String baseline = tailCursor(bobToken);

    OffsetDateTime tiedAt = OffsetDateTime.now(ZoneOffset.UTC);
    String tiedA = seedMessageAt(aliceSub, bobSub, tiedAt);
    String tiedB = seedMessageAt(aliceSub, bobSub, tiedAt);

    var page1 = getHistory(bobToken, baseline, 1);
    assertThat(page1.messages()).hasSize(1);
    assertThat(page1.hasMore()).isTrue();

    var page2 = getHistory(bobToken, page1.messages().get(0).cursor(), 1);
    assertThat(page2.messages()).hasSize(1);

    assertThat(List.of(page1.messages().get(0).messageId(), page2.messages().get(0).messageId()))
        .as("both tied rows must appear exactly once, with no skip or duplicate")
        .containsExactlyInAnyOrder(tiedA, tiedB);
  }

  @Test
  void returnsOnlyMessagesAddressedToCaller() {
    String bobToken = Tokens.accessToken(issuerUri(), "bob");
    String bobSub = Tokens.subject(bobToken);
    String aliceToken = Tokens.accessToken(issuerUri(), "alice");
    String aliceSub = Tokens.subject(aliceToken);
    // No caller-supplied recipient parameter exists (structurally prevented); scoping to another
    // user's inbox is verified directly against a synthetic recipient sub — recipient_sub has no
    // FK to a real Keycloak account, so no third seeded user is needed.
    String otherUserSub = "carol-sub-" + UUID.randomUUID();
    String baseline = tailCursor(bobToken);

    String toBob = seedMessage(aliceSub, bobSub);
    seedMessage(aliceSub, otherUserSub);

    var page = getHistory(bobToken, baseline, 50);
    assertThat(page.messages()).extracting(HistoryItem::messageId).containsExactly(toBob);
  }

  @Test
  void malformedCursor_returns400() {
    String bobToken = Tokens.accessToken(issuerUri(), "bob");
    client()
        .get()
        .uri("/v1/messages?cursor=not-base64")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST)
        .expectBody()
        .jsonPath("$.errors[0].code")
        .isEqualTo("INVALID_REQUEST");
  }

  @Test
  void unauthenticated_returns401() {
    client().get().uri("/v1/messages").exchange().expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
