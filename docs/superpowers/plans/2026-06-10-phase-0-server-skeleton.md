# Server Skeleton (Auth + Messaging Round-Trip) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the server half of the walking skeleton: an authenticated WebSocket accepts an opaque ciphertext envelope, persists it (byte-for-byte), publishes it to Kafka keyed by recipient, and a consumer delivers it to the recipient's connected session — all verified on Testcontainers.

**Architecture:** Hexagonal + DDD per `server/docs/ARCHITECTURE_GUIDE.md`, built on Plan 1's foundation. The domain `Message` carries ciphertext as opaque bytes (no plaintext field, no decrypt) per §0.6.3. Inbound WebSocket adapter → `RouteMessageUseCase` → persistence + Kafka publisher ports; a Kafka consumer fans the envelope out to recipient WebSocket sessions. Auth is JWKS-based Spring Security resource server (§ `iam/CLAUDE.md`).

**Tech Stack:** Spring Boot 4, Spring Security (OAuth2 resource server), Spring WebSocket, Spring Kafka, **Avro + Confluent Schema Registry** for Kafka record values (`queue/CLAUDE.md`), JPA/Postgres (Plan 1), Testcontainers (Postgres + Kafka + Schema Registry + Keycloak).

**Workflow:** Branch `feature/phase-0-server-skeleton` off `main` (after Plan 1 is merged). Run `./gradlew spotlessApply` before every commit (Plan 1, Task 1 note). Follow root `CLAUDE.md` workflow + quality gates. Docker required for integration tests.

**Scope note:** This is the *thin* skeleton. Richer patterns the guide mandates for real features — `DomainClock`, aggregate `@Version`/optimistic-lock retry, the status state machine, the domain-event orchestrator, Karate BDD — arrive with the real messaging slices (roadmap Slices 2–3), not here. The privacy invariants (§0.6.3/§0.6.4) apply now.

---

## File Structure (base package `com.cloak.server`)

- `iam/realm/cloak-realm.json` — add seed users + a test-only client.
- `common/config/AuthProperties.java`, `common/config/SecurityConfig.java` — typed auth config + resource server.
- `adapter/input/rest/identity/WhoAmIController.java` — trivial authenticated endpoint (auth probe).
- `domain/message/Message.java`, `Ciphertext.java`, `MessageId.java` — ciphertext-only aggregate.
- `port/output/message/MessageRepositoryPort.java`, `MessagePublisherPort.java`.
- `adapter/output/database/message/{EncryptedMessageEntity,SpringDataMessageRepository,MessageRepositoryAdapter,MessageRowMapper}.java`.
- `adapter/output/kafka/message/{OutboundEnvelope,KafkaMessagePublisherAdapter}.java`.
- `common/config/KafkaTopicsConfig.java` — `NewTopic` beans (idempotent).
- `usecase/RouteMessageUseCase.java`, `RouteMessageCommand.java`.
- `adapter/input/websocket/{WebSocketConfig,WebSocketSessionRegistry,MessageWebSocketHandler,InboundEnvelope}.java`.
- `adapter/input/kafka/OutboundMessageConsumer.java`.
- Integration tests under `src/integrationTest/java/com/cloak/server/...`, plus a `support/Tokens.java` helper.

---

### Task 1: Seed Keycloak realm users + test client

**Files:**
- Modify: `iam/realm/cloak-realm.json`
- Modify: `iam/README.md`

- [ ] **Step 1: Add two users and a test-only direct-access client**

In `iam/realm/cloak-realm.json`, add a `users` array and append a `cloak-test` client to `clients` (keep `cloak-ios`/`cloak-api` unchanged). The `cloak-test` client exists **only to mint tokens in integration tests** (direct access grants); `cloak-ios` stays without direct grants.

Add to the top-level object:
```json
"users": [
  {
    "username": "alice",
    "enabled": true,
    "email": "alice@example.com",
    "emailVerified": true,
    "firstName": "Alice",
    "lastName": "Example",
    "requiredActions": [],
    "credentials": [{ "type": "password", "value": "password", "temporary": false }]
  },
  {
    "username": "bob",
    "enabled": true,
    "email": "bob@example.com",
    "emailVerified": true,
    "firstName": "Bob",
    "lastName": "Example",
    "requiredActions": [],
    "credentials": [{ "type": "password", "value": "password", "temporary": false }]
  }
]
```
> **`firstName`/`lastName`/`requiredActions: []` are required.** Keycloak 26's default user-profile
> marks `firstName` and `lastName` as required attributes; an imported user missing them (or carrying
> a pending required action) is "not fully set up" and the password grant fails with
> `invalid_grant: "Account is not fully set up"`. These fields were added when Task 2 first exercised
> the token mint (the only place that surfaces this).
Add to the `clients` array:
```json
{
  "clientId": "cloak-test",
  "name": "Cloak integration-test client (test only)",
  "enabled": true,
  "protocol": "openid-connect",
  "publicClient": true,
  "standardFlowEnabled": false,
  "directAccessGrantsEnabled": true,
  "protocolMappers": [
    {
      "name": "audience-cloak-api",
      "protocol": "openid-connect",
      "protocolMapper": "oidc-audience-mapper",
      "config": { "included.client.audience": "cloak-api", "id.token.claim": "false", "access.token.claim": "true" }
    }
  ]
}
```

- [ ] **Step 2: Validate JSON**

Run (repo root): `python3 -c "import json; json.load(open('iam/realm/cloak-realm.json')); print('ok')"`
Expected: `ok`.

- [ ] **Step 3: Update `iam/README.md`**

Per `iam/CLAUDE.md` principle 5: any change to what the realm exposes must update `iam/README.md` in the same change set. Add a row for `cloak-test` to the client table and a note that the realm seeds two local test users (`alice`/`bob`, password `password`) for integration tests only.

- [ ] **Step 4: Commit**

```bash
git add iam/realm/cloak-realm.json iam/README.md docs/superpowers/plans/2026-06-10-phase-0-server-skeleton.md
git commit -m "$(printf 'feat(iam): seed alice/bob users and cloak-test client for Phase 0\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 2: Resource-server auth + auth probe endpoint

**Files:**
- Modify: `server/build.gradle`, `server/src/main/resources/application.yml`
- Create: `common/config/AuthProperties.java`, `common/config/SecurityConfig.java`, `adapter/input/rest/identity/WhoAmIController.java`
- Create test: `WhoAmIIntegrationTest.java`, `support/Tokens.java`, and extend `IntegrationTestBase` to import the realm.

> **Implemented (Boot 4.0.6 / Spring Security 7 reconciliations):** the snippets below reflect the
> committed code. Three deviations from the original plan draft were required and are noted inline:
> (a) `TestRestTemplate` was removed in Spring Boot 4 — the test uses Spring Framework 7's
> `RestTestClient.bindToServer()` instead; (b) `JwtDecoders.fromIssuerLocation(...)` is typed to
> return `JwtDecoder`, so it is cast to `NimbusJwtDecoder`; (c) the seeded `alice`/`bob` users
> needed `firstName`/`lastName` + `requiredActions: []` in `iam/realm/cloak-realm.json` — without
> them Keycloak 26's default user-profile rejects the password grant with
> `invalid_grant: "Account is not fully set up"` (fixed in Task 1's realm file as part of this task).
> The `cloak.auth.issuer-uri` dynamic property was already registered by Plan 1; it was refactored
> to the `issuerUri()` helper and the resource-server `jwt.issuer-uri` registration added alongside.

- [ ] **Step 1: Add the resource-server dependency**

In `server/build.gradle` `dependencies {}` add (the actuator starter is needed by Step 3's
`/actuator/health` permit):
```groovy
implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
implementation 'org.springframework.boot:spring-boot-starter-actuator'
```

- [ ] **Step 2: Import the realm into the test Keycloak + add a token helper**

In `IntegrationTestBase.java` (Plan 1), import the seeded realm on the Keycloak field, expose the
issuer via a helper, and register both issuer properties dynamically (the `cloak.auth.issuer-uri`
add was already present from Plan 1 — refactor it to the helper, do not duplicate):
```java
static final KeycloakContainer KEYCLOAK =
    new KeycloakContainer("quay.io/keycloak/keycloak:26.0")
        .withRealmImportFile("/realms/cloak-realm.json");

protected static String issuerUri() {
  return KEYCLOAK.getAuthServerUrl() + "/realms/cloak";
}

// in props(...):
r.add("cloak.auth.issuer-uri", IntegrationTestBase::issuerUri);
r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", IntegrationTestBase::issuerUri);
```
The realm export stays single-sourced in `iam/`; a Gradle `processIntegrationTestResources` copy
makes it a build input for tests (no hand-copied file in source control) — add to `build.gradle`:
```groovy
tasks.named('processIntegrationTestResources') {
    from('../iam/realm') { into 'realms' }
}
```
This copies `iam/realm/cloak-realm.json` onto the integrationTest classpath under `realms/`; the
container reads `/realms/cloak-realm.json`.

Create `src/integrationTest/java/com/cloak/server/support/Tokens.java`:
```java
package com.cloak.server.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class Tokens {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Tokens() {}

    /** Mints an access token for a seeded user via the cloak-test client (direct grant). */
    public static String accessToken(String issuerUri, String username) {
        try {
            String body = "grant_type=password&client_id=cloak-test"
                + "&username=" + username + "&password=password&scope=openid";
            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(issuerUri + "/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
            JsonNode json = MAPPER.readTree(resp.body());
            JsonNode token = json.get("access_token");
            if (token == null) {
                throw new IllegalStateException("no access_token in response: " + resp.body());
            }
            return token.asText();
        } catch (Exception e) {
            throw new IllegalStateException("token mint failed", e);
        }
    }
}
```
(The `issuerUri()` helper is added to `IntegrationTestBase` in Step 2 above.)

- [ ] **Step 3: Typed auth config + security filter chain**

Create `common/config/AuthProperties.java`:
```java
package com.cloak.server.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cloak.auth")
public record AuthProperties(String issuerUri, String audience) {}
```
Create `common/config/SecurityConfig.java`:
```java
package com.cloak.server.common.config;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(jwt -> {}));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(AuthProperties props) {
        // Spring Security 7: fromIssuerLocation returns JwtDecoder; cast to set the validator.
        NimbusJwtDecoder decoder =
            (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(props.issuerUri());
        OAuth2TokenValidator<Jwt> withIssuer =
            JwtValidators.createDefaultWithIssuer(props.issuerUri());
        OAuth2TokenValidator<Jwt> audience = jwt ->
            jwt.getAudience().contains(props.audience())
                ? org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success()
                : org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                    new org.springframework.security.oauth2.core.OAuth2Error(
                        "invalid_audience", "Missing required audience", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, audience));
        return decoder;
    }
}
```
Add to `application.yml`:
```yaml
cloak:
  auth:
    issuer-uri: http://localhost:8081/realms/cloak
    audience: cloak-api
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8081/realms/cloak
```
The `application.yml` `cloak:` block is added at the top level; `spring.security.oauth2...` merges
into the existing `spring:` mapping. The dynamic test wiring for both issuer properties and the
`spring-boot-starter-actuator` dependency are already covered in Step 2 / Step 1 above.

- [ ] **Step 4: Auth probe controller**

Create `adapter/input/rest/identity/WhoAmIController.java`:
```java
package com.cloak.server.adapter.input.rest.identity;

import java.util.Map;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WhoAmIController {
    @GetMapping("/v1/me")
    Map<String, String> me(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("sub", jwt.getSubject());
    }
}
```

- [ ] **Step 5: Write the failing auth integration test**

Create `src/integrationTest/java/com/cloak/server/WhoAmIIntegrationTest.java`. **Boot 4 removed
`TestRestTemplate`**, so this uses Spring Framework 7's `RestTestClient.bindToServer()` against the
random port:
```java
package com.cloak.server;

import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

class WhoAmIIntegrationTest extends IntegrationTestBase {

    @LocalServerPort int port;

    private RestTestClient client() {
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void noToken_returns401() {
        client().get().uri("/v1/me").exchange().expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validToken_returnsSub() {
        String token = Tokens.accessToken(issuerUri(), "alice");
        client()
            .get()
            .uri("/v1/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.sub")
            .exists();
    }
}
```

- [ ] **Step 6: Run it** (Docker required)

Run: `./gradlew spotlessApply integrationTest --tests '*WhoAmIIntegrationTest'`
Expected: both tests PASS (401 without token, 200 + sub with a real Keycloak token).

- [ ] **Step 7: Commit**

```bash
git add build.gradle src/main src/integrationTest
git commit -m "$(printf 'feat(auth): JWKS resource server + /v1/me probe (integration-tested)\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 3: Domain — ciphertext-only Message aggregate

**Files:**
- Create: `domain/message/{Ciphertext,MessageId,Message}.java`
- Create test: `src/test/java/com/cloak/server/domain/message/MessageTest.java`

- [ ] **Step 1: Write the failing unit test**

Create `src/test/java/com/cloak/server/domain/message/MessageTest.java`:
```java
package com.cloak.server.domain.message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MessageTest {
    @Test
    void ciphertextToString_neverLeaksBytes() {
        var c = new Ciphertext(new byte[] {1, 2, 3, 4});
        assertThat(c.toString()).isEqualTo("Ciphertext[4 bytes]").doesNotContain("1, 2");
    }

    @Test
    void message_exposesRoutingMetadataAndOpaqueCiphertext() {
        var msg = Message.create(
            new MessageId("11111111-1111-1111-1111-111111111111"),
            "alice-sub", "bob-sub", "device-1",
            new Ciphertext(new byte[] {9, 9}));
        assertThat(msg.recipientSub()).isEqualTo("bob-sub");
        assertThat(msg.ciphertext().value()).containsExactly(9, 9);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*MessageTest'`
Expected: FAIL (classes not found).

- [ ] **Step 3: Implement the domain types**

Create `domain/message/Ciphertext.java`:
```java
package com.cloak.server.domain.message;

import java.util.Objects;

/** Opaque encrypted bytes. The server never reads or decrypts these. */
public record Ciphertext(byte[] value) {
    public Ciphertext {
        Objects.requireNonNull(value, "ciphertext");
    }

    @Override
    public String toString() {
        return "Ciphertext[" + value.length + " bytes]"; // never dump bytes (§0.6.4)
    }
}
```
Create `domain/message/MessageId.java`:
```java
package com.cloak.server.domain.message;

import java.util.Objects;

public record MessageId(String value) {
    public MessageId {
        Objects.requireNonNull(value, "messageId");
    }
}
```
Create `domain/message/Message.java`:
```java
package com.cloak.server.domain.message;

/** Routing-metadata + opaque ciphertext. No plaintext field, no decrypt (§0.6.3). */
public final class Message {
    private final MessageId id;
    private final String senderSub;
    private final String recipientSub;
    private final String deviceId;
    private final Ciphertext ciphertext;

    private Message(MessageId id, String senderSub, String recipientSub,
                    String deviceId, Ciphertext ciphertext) {
        this.id = id;
        this.senderSub = senderSub;
        this.recipientSub = recipientSub;
        this.deviceId = deviceId;
        this.ciphertext = ciphertext;
    }

    public static Message create(MessageId id, String senderSub, String recipientSub,
                                 String deviceId, Ciphertext ciphertext) {
        return new Message(id, senderSub, recipientSub, deviceId, ciphertext);
    }

    public MessageId id() {
        return id;
    }

    public String senderSub() {
        return senderSub;
    }

    public String recipientSub() {
        return recipientSub;
    }

    public String deviceId() {
        return deviceId;
    }

    public Ciphertext ciphertext() {
        return ciphertext;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew spotlessApply test --tests '*MessageTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/cloak/server/domain src/test/java/com/cloak/server/domain
git commit -m "$(printf 'feat(domain): ciphertext-only Message aggregate\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 4: Persistence adapter (port + JPA + mapper)

**Files:**
- Create: `port/output/message/MessageRepositoryPort.java`
- Create: `adapter/output/database/message/{EncryptedMessageEntity,SpringDataMessageRepository,MessageRowMapper,MessageRepositoryAdapter}.java`
- Create test: `src/integrationTest/java/com/cloak/server/MessagePersistenceIntegrationTest.java`

- [x] **Step 1: Define the output port**

Create `port/output/message/MessageRepositoryPort.java`:
```java
package com.cloak.server.port.output.message;

import com.cloak.server.domain.message.Message;
import com.cloak.server.domain.message.MessageId;
import java.util.Optional;

public interface MessageRepositoryPort {
    void save(Message message);

    Optional<Message> find(MessageId id);
}
```

- [x] **Step 2: Entity + Spring Data repo + mapper + adapter**

Create `adapter/output/database/message/EncryptedMessageEntity.java`:
```java
package com.cloak.server.adapter.output.database.message;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "encrypted_message")
public class EncryptedMessageEntity {
    @Id
    private UUID id;

    @Column(name = "sender_sub", nullable = false)
    private String senderSub;

    @Column(name = "recipient_sub", nullable = false)
    private String recipientSub;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "ciphertext", nullable = false)
    private byte[] ciphertext;

    protected EncryptedMessageEntity() {}

    public EncryptedMessageEntity(UUID id, String senderSub, String recipientSub,
                                  UUID deviceId, byte[] ciphertext) {
        this.id = id;
        this.senderSub = senderSub;
        this.recipientSub = recipientSub;
        this.deviceId = deviceId;
        this.ciphertext = ciphertext;
    }

    public UUID getId() {
        return id;
    }

    public String getSenderSub() {
        return senderSub;
    }

    public String getRecipientSub() {
        return recipientSub;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public byte[] getCiphertext() {
        return ciphertext;
    }
}
```
Create `adapter/output/database/message/SpringDataMessageRepository.java`:
```java
package com.cloak.server.adapter.output.database.message;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataMessageRepository extends JpaRepository<EncryptedMessageEntity, UUID> {}
```
Create `adapter/output/database/message/MessageRowMapper.java`:
```java
package com.cloak.server.adapter.output.database.message;

import com.cloak.server.domain.message.*;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class MessageRowMapper {
    EncryptedMessageEntity toEntity(Message m) {
        return new EncryptedMessageEntity(
            UUID.fromString(m.id().value()),
            m.senderSub(),
            m.recipientSub(),
            m.deviceId() == null ? null : UUID.fromString(m.deviceId()),
            m.ciphertext().value());
    }

    Message toDomain(EncryptedMessageEntity e) {
        return Message.create(
            new MessageId(e.getId().toString()),
            e.getSenderSub(),
            e.getRecipientSub(),
            e.getDeviceId() == null ? null : e.getDeviceId().toString(),
            new Ciphertext(e.getCiphertext()));
    }
}
```
Create `adapter/output/database/message/MessageRepositoryAdapter.java`:
```java
package com.cloak.server.adapter.output.database.message;

import com.cloak.server.domain.message.Message;
import com.cloak.server.domain.message.MessageId;
import com.cloak.server.port.output.message.MessageRepositoryPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class MessageRepositoryAdapter implements MessageRepositoryPort {
    private final SpringDataMessageRepository jpa;
    private final MessageRowMapper mapper;

    MessageRepositoryAdapter(SpringDataMessageRepository jpa, MessageRowMapper mapper) {
        this.jpa = jpa;
        this.mapper = mapper;
    }

    @Override
    public void save(Message message) {
        jpa.save(mapper.toEntity(message));
    }

    @Override
    public Optional<Message> find(MessageId id) {
        return jpa.findById(UUID.fromString(id.value())).map(mapper::toDomain);
    }
}
```

- [x] **Step 3: Failing integration test — round-trips ciphertext byte-for-byte**

Create `src/integrationTest/java/com/cloak/server/MessagePersistenceIntegrationTest.java`:
```java
package com.cloak.server;

import com.cloak.server.domain.message.*;
import com.cloak.server.port.output.message.MessageRepositoryPort;
import com.cloak.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class MessagePersistenceIntegrationTest extends IntegrationTestBase {

    @Autowired MessageRepositoryPort repository;

    @Test
    void savesAndReloadsCiphertextUnchanged() {
        byte[] cipher = {4, 8, 15, 16, 23, 42};
        var id = new MessageId("22222222-2222-2222-2222-222222222222");
        // deviceId is null: encrypted_message.device_id has an FK to device(id) (added in
        // Plan 1's code review), and no device row is seeded here. The test's intent is the
        // byte-for-byte ciphertext round-trip; multi-device targeting arrives in a later slice.
        repository.save(Message.create(id, "alice-sub", "bob-sub", null, new Ciphertext(cipher)));

        var loaded = repository.find(id).orElseThrow();
        assertThat(loaded.ciphertext().value()).containsExactly(cipher);
        assertThat(loaded.recipientSub()).isEqualTo("bob-sub");
    }
}
```

- [x] **Step 4: Run** (Docker)

Run: `./gradlew spotlessApply integrationTest --tests '*MessagePersistenceIntegrationTest'`
Expected: PASS (the persisted `ciphertext` column equals the input bytes exactly — §0.6.4 privacy assertion).

PASSED with no Hibernate/Boot 4 mapping deviations: the `@Entity` started cleanly under
`ddl-auto: validate` (byte[]↔bytea and the nullable `device_id` UUID validated without error), and
the full `./gradlew integrationTest` stayed green (ContextLoads + WhoAmI unaffected). The integration
test uses a **null deviceId** because `encrypted_message.device_id` has an FK to `device(id)` (added
in Plan 1's review) and no device row is seeded here; the test's intent is the byte-for-byte
ciphertext round-trip. No migration changes were needed.

- [x] **Step 5: Commit**

```bash
git add src/main src/integrationTest
git commit -m "$(printf 'feat(persistence): Message repository port + JPA adapter\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 5: Kafka publisher port + adapter + topics

**Files:**
- Create: `port/output/message/MessagePublisherPort.java`
- Create: `adapter/output/kafka/message/{OutboundEnvelope (Avro-generated),KafkaMessagePublisherAdapter}.java`
- Create: `common/config/KafkaTopicsConfig.java`, `common/config/KafkaProducerConfig.java`
- Create: `src/main/avro/OutboundEnvelope.avsc`
- Modify: `server/build.gradle`, `application.yml` (kafka producer/consumer Avro serdes)
- Create test: `src/integrationTest/java/com/cloak/server/MessagePublishIntegrationTest.java`
- Modify: `src/integrationTest/java/com/cloak/server/support/IntegrationTestBase.java` (Schema Registry container)

> **Implemented (Gradle 9 / Boot 4 / Confluent reconciliations):** the snippets below reflect the
> committed code. Five deviations from the original draft were required and are noted inline:
> (a) **`org.springframework.boot:spring-boot-kafka`** had to be added to `dependencies {}`. Boot 4
> split Kafka auto-configuration out of `spring-boot-autoconfigure` into this dedicated module
> (exactly like `spring-boot-flyway`). Without it `KafkaProperties`/`KafkaAutoConfiguration` are
> absent, **no `KafkaTemplate` is bound**, and the context fails with
> `NoSuchBeanDefinitionException` for the template. (b) Boot's auto-configured template is
> `KafkaTemplate<Object, Object>`, which does **not** satisfy the adapter's
> `KafkaTemplate<String, OutboundEnvelope>` injection point, so a typed `ProducerFactory` +
> `KafkaTemplate` are declared in a new **`common/config/KafkaProducerConfig.java`** (built from the
> resolved `spring.kafka.producer.*` props via `KafkaProperties.buildProducerProperties()` — no-arg
> in Boot 4). (c) In the verification test, `KafkaConsumer` does **not** call `configure()` on
> deserializer *instances* passed to its constructor, so the `KafkaAvroDeserializer` must be
> `configure(props, false)`-d explicitly or it throws `InvalidConfigurationException: SchemaRegistryClient not found`.
> (d) The `davidmc24` Avro plugin **v1.9.1 works on Gradle 9.5.1** (BUILD SUCCESSFUL; only generic
> Gradle-10 deprecation warnings, no plugin failure) — the fallback `avro-tools` JavaExec was not
> needed. (e) The Avro-generated `OutboundEnvelope` is excluded from JaCoCo (Step 1b below).
> The Schema Registry `ConfluentKafkaContainer.withListener("kafka:19092")` API behaved as written
> under Testcontainers 1.21.4; the registry successfully registered the schema on first publish.
>
> **Pre-existing, out-of-scope note (Task 8 will own it):** `jacocoTestReport` /
> `jacocoTestCoverageVerification` fail on **JaCoCo 0.8.12** with
> `IllegalArgumentException: Unsupported class file major version 69` — JaCoCo 0.8.12 cannot read
> Java 25 bytecode. This is branch-wide (the failure surfaces first on the Task-2 `WhoAmIController`
> class, not on anything from Task 5) and does **not** affect `integrationTest` execution (tests run
> green). The coverage gate is a Task 8 concern (likely a JaCoCo bump). Task 5's only coverage
> responsibility — excluding the generated Avro class from the denominator — is done.

- [ ] **Step 1: Avro tooling + the envelope schema (Confluent Schema Registry)**

Record values are **Avro** (see `queue/CLAUDE.md` → Serialization). Add the Confluent repo + Avro tooling to `server/build.gradle`:
- `plugins {}`: `id 'com.github.davidmc24.gradle.plugin.avro' version '1.9.1'`
- `repositories {}` (alongside `mavenCentral()`): `maven { url 'https://packages.confluent.io/maven/' }`
- `dependencies {}`:
```groovy
// Boot 4 split Kafka auto-config into a dedicated module (like spring-boot-flyway); required so a
// KafkaTemplate is bound from spring.kafka.* and the typed template (Step 2) can be built.
implementation 'org.springframework.boot:spring-boot-kafka'
implementation 'org.apache.avro:avro:1.11.4'
implementation 'io.confluent:kafka-avro-serializer:7.6.0'
```
Create the schema `server/src/main/avro/OutboundEnvelope.avsc` (the plugin generates the Java class into the `main` source set, package = `namespace`):
```json
{
  "type": "record",
  "name": "OutboundEnvelope",
  "namespace": "com.cloak.server.adapter.output.kafka.message",
  "doc": "Kafka delivery envelope. Routing metadata + opaque ciphertext only.",
  "fields": [
    { "name": "messageId", "type": "string" },
    { "name": "toSub", "type": "string" },
    { "name": "fromSub", "type": "string" },
    { "name": "deviceId", "type": ["null", "string"], "default": null },
    { "name": "ciphertext", "type": "bytes", "doc": "opaque; the server never decrypts" }
  ]
}
```
Run `./gradlew generateAvroJava compileJava`. Expected: generated `OutboundEnvelope` on the classpath; **BUILD SUCCESSFUL**. (The `.avsc` is the in-repo source of truth; do not hand-write the Java class.) ✅ Confirmed working on Gradle 9.5.1 with plugin v1.9.1.

- [ ] **Step 1b: Exclude the generated Avro class from JaCoCo**

The generated `OutboundEnvelope` lands in `com.cloak.server.adapter.output.kafka.message` — outside the
existing `**/config/**` exclusion — so JaCoCo would measure generated code and fail the ≥90% gate.
Root `CLAUDE.md` excludes generated code from the denominator. Add to `coverageExclusions` in
`build.gradle`:
```groovy
// Avro-generated envelope (and its nested Builder/types) — generated code, excluded per CLAUDE.md.
'com/cloak/server/adapter/output/kafka/message/OutboundEnvelope*.class'
```
Verified the generated class files are `OutboundEnvelope.class` and `OutboundEnvelope$Builder.class`
under `build/classes/java/main/...`; the `OutboundEnvelope*.class` glob matches both.

- [ ] **Step 2: Port + adapter + topics + config**

Besides the port, topics, and adapter below, a typed Kafka template is required: Boot's
auto-configured `KafkaTemplate<Object, Object>` does not satisfy the adapter's
`KafkaTemplate<String, OutboundEnvelope>` injection point. Create `common/config/KafkaProducerConfig.java`:
```java
package com.cloak.server.common.config;

import com.cloak.server.adapter.output.kafka.message.OutboundEnvelope;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
public class KafkaProducerConfig {
    @Bean
    ProducerFactory<String, OutboundEnvelope> envelopeProducerFactory(KafkaProperties properties) {
        return new DefaultKafkaProducerFactory<>(properties.buildProducerProperties());
    }

    @Bean
    KafkaTemplate<String, OutboundEnvelope> envelopeKafkaTemplate(
            ProducerFactory<String, OutboundEnvelope> factory) {
        return new KafkaTemplate<>(factory);
    }
}
```

Create `port/output/message/MessagePublisherPort.java`:
```java
package com.cloak.server.port.output.message;

import com.cloak.server.domain.message.Message;

public interface MessagePublisherPort {
    void publish(Message message);
}
```
Create `common/config/KafkaTopicsConfig.java`:
```java
package com.cloak.server.common.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {
    @Bean
    NewTopic outbound() {
        return TopicBuilder.name("cloak.messages.outbound").partitions(12).replicas(1).build();
    }
}
```
Create `adapter/output/kafka/message/KafkaMessagePublisherAdapter.java`:
```java
package com.cloak.server.adapter.output.kafka.message;

import com.cloak.server.domain.message.Message;
import com.cloak.server.port.output.message.MessagePublisherPort;
import java.nio.ByteBuffer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
class KafkaMessagePublisherAdapter implements MessagePublisherPort {
    static final String TOPIC = "cloak.messages.outbound";
    private final KafkaTemplate<String, OutboundEnvelope> kafka; // OutboundEnvelope is the Avro type

    KafkaMessagePublisherAdapter(KafkaTemplate<String, OutboundEnvelope> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publish(Message m) {
        var env = OutboundEnvelope.newBuilder()
            .setMessageId(m.id().value())
            .setToSub(m.recipientSub())
            .setFromSub(m.senderSub())
            .setDeviceId(m.deviceId())
            .setCiphertext(ByteBuffer.wrap(m.ciphertext().value()))
            .build();
        // Keyed by recipient sub for fan-out / per-recipient ordering (queue/CLAUDE.md).
        kafka.send(TOPIC, m.recipientSub(), env);
    }
}
```
Add to `application.yml`:
```yaml
spring:
  kafka:
    properties:
      schema.registry.url: http://localhost:8085
    consumer:
      group-id: cloak-server
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: io.confluent.kafka.serializers.KafkaAvroDeserializer
      properties:
        specific.avro.reader: true
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: io.confluent.kafka.serializers.KafkaAvroSerializer
    listener:
      ack-mode: record
```

- [ ] **Step 3: Extend the Testcontainers harness with Schema Registry**

Avro serdes need the registry. In `IntegrationTestBase` (Plan 1 / Task 2), put Kafka + Schema Registry on a shared network and expose the registry URL. Replace the `KAFKA` field and add the registry:
```java
static final org.testcontainers.containers.Network NET =
    org.testcontainers.containers.Network.newNetwork();

static final ConfluentKafkaContainer KAFKA =
    new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
        .withNetwork(NET)
        .withListener("kafka:19092");   // in-network listener for Schema Registry

static final org.testcontainers.containers.GenericContainer<?> SCHEMA_REGISTRY =
    new org.testcontainers.containers.GenericContainer<>(
            DockerImageName.parse("confluentinc/cp-schema-registry:7.6.0"))
        .withNetwork(NET)
        .withExposedPorts(8081)
        .dependsOn(KAFKA)
        .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
        .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:19092")
        .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081");
```
Add `SCHEMA_REGISTRY` to `Startables.deepStart(...)`, add a helper, and register the dynamic property:
```java
protected static String schemaRegistryUrl() {
    return "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081);
}

// The container fields are package-private; tests in com.cloak.server (not ..support) reach the
// broker via this protected helper rather than the KAFKA field directly.
protected static String kafkaBootstrapServers() {
    return KAFKA.getBootstrapServers();
}

// in props(...):
r.add("spring.kafka.properties.schema.registry.url", IntegrationTestBase::schemaRegistryUrl);
```
The verification test consumes via `kafkaBootstrapServers()` (the `KAFKA` field is not visible
outside `..support`).

- [ ] **Step 4: Failing integration test — publishes a keyed Avro envelope**

Create `src/integrationTest/java/com/cloak/server/MessagePublishIntegrationTest.java`:
```java
package com.cloak.server;

import com.cloak.server.adapter.output.kafka.message.OutboundEnvelope;
import com.cloak.server.domain.message.*;
import com.cloak.server.port.output.message.MessagePublisherPort;
import com.cloak.server.support.IntegrationTestBase;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class MessagePublishIntegrationTest extends IntegrationTestBase {

    @Autowired MessagePublisherPort publisher;

    @Test
    void publishesAvroEnvelopeKeyedByRecipient() {
        byte[] cipher = {1, 2, 3};
        publisher.publish(Message.create(
            new MessageId("44444444-4444-4444-4444-444444444444"),
            "alice-sub", "bob-sub", null, new Ciphertext(cipher)));

        var props = Map.<String, Object>of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "test-verify",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            "schema.registry.url", schemaRegistryUrl(),
            KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        // KafkaConsumer does NOT configure() deserializer instances passed to its constructor, so
        // the Avro deserializer must be configured explicitly with the registry URL — otherwise
        // it throws InvalidConfigurationException: SchemaRegistryClient not found.
        var valueDeserializer = new KafkaAvroDeserializer();
        valueDeserializer.configure(props, false);
        try (var consumer = new KafkaConsumer<String, Object>(
                props, new StringDeserializer(), valueDeserializer)) {
            consumer.subscribe(List.of("cloak.messages.outbound"));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                var records = consumer.poll(Duration.ofMillis(500));
                assertThat(records.count()).isGreaterThan(0);
                records.forEach(r -> {
                    assertThat(r.key()).isEqualTo("bob-sub");
                    var env = (OutboundEnvelope) r.value();
                    assertThat(env.getCiphertext().array()).containsExactly(cipher);
                });
            });
        }
    }
}
```

- [ ] **Step 4: Run** (Docker)

Run: `./gradlew spotlessApply integrationTest --tests '*MessagePublishIntegrationTest'`
Expected: PASS (record keyed by `bob-sub`, ciphertext present as base64).

- [ ] **Step 5: Commit**

```bash
git add src/main src/integrationTest
git commit -m "$(printf 'feat(kafka): message publisher port + adapter keyed by recipient\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 6: RouteMessageUseCase (persist then publish)

**Files:**
- Create: `usecase/{RouteMessageCommand,RouteMessageUseCase}.java`
- Create: `common/config/PersistenceConfig.java` (TransactionTemplate bean — see implementation note)
- Create test: `src/integrationTest/java/com/cloak/server/RouteMessageIntegrationTest.java`

> **Implemented (Boot 4.0.6 reconciliation):** Spring Boot does NOT auto-configure a `TransactionTemplate`
> bean when JPA is present — only the `PlatformTransactionManager` is auto-configured. `PersistenceConfig`
> was therefore required to declare the `TransactionTemplate` bean (confirming the plan's "if no bean is
> found" note applies here). Implementation followed the plan exactly: persist inside
> `tx.executeWithoutResult(...)`, publish outside/after the lambda (post-commit, §5.2). No wildcard
> imports used (spotlessApply expanded them). All integration tests pass:
> `RouteMessageIntegrationTest.persistsThenIsRetrievable` + the full suite (6 tests, 0 failures).
> The known JaCoCo 0.8.12 / Java 25 `IllegalClassFormatException` warnings are pre-existing and
> do not affect test execution; coverage gate is deferred to Task 8.

- [x] **Step 1: Command + use case**

Create `usecase/RouteMessageCommand.java`:
```java
package com.cloak.server.usecase;

public record RouteMessageCommand(
    String messageId, String senderSub, String recipientSub, String deviceId, byte[] ciphertext) {}
```
Create `usecase/RouteMessageUseCase.java`:
```java
package com.cloak.server.usecase;

import com.cloak.server.domain.message.*;
import com.cloak.server.port.output.message.MessagePublisherPort;
import com.cloak.server.port.output.message.MessageRepositoryPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class RouteMessageUseCase {
    private final MessageRepositoryPort repository;
    private final MessagePublisherPort publisher;
    private final TransactionTemplate tx;

    public RouteMessageUseCase(MessageRepositoryPort repository,
                               MessagePublisherPort publisher, TransactionTemplate tx) {
        this.repository = repository;
        this.publisher = publisher;
        this.tx = tx;
    }

    public void route(RouteMessageCommand cmd) {
        var message = Message.create(
            new MessageId(cmd.messageId()), cmd.senderSub(), cmd.recipientSub(),
            cmd.deviceId(), new Ciphertext(cmd.ciphertext()));
        tx.executeWithoutResult(s -> repository.save(message)); // persist in tx
        publisher.publish(message);                              // publish after commit (§5.2)
    }
}
```
`TransactionTemplate` is NOT auto-configured by Spring Boot even with JPA; `PersistenceConfig` declares it:
```java
@Bean
TransactionTemplate transactionTemplate(org.springframework.transaction.PlatformTransactionManager tm) {
    return new TransactionTemplate(tm);
}
```
(In `common/config/PersistenceConfig.java`.)

- [x] **Step 2: Failing integration test**

Create `src/integrationTest/java/com/cloak/server/RouteMessageIntegrationTest.java`:
```java
package com.cloak.server;

import com.cloak.server.domain.message.MessageId;
import com.cloak.server.port.output.message.MessageRepositoryPort;
import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.usecase.RouteMessageCommand;
import com.cloak.server.usecase.RouteMessageUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class RouteMessageIntegrationTest extends IntegrationTestBase {

    @Autowired RouteMessageUseCase useCase;
    @Autowired MessageRepositoryPort repository;

    @Test
    void persistsThenIsRetrievable() {
        byte[] cipher = {7, 7, 7};
        var id = "55555555-5555-5555-5555-555555555555";
        useCase.route(new RouteMessageCommand(id, "alice-sub", "bob-sub", null, cipher));
        assertThat(repository.find(new MessageId(id)).orElseThrow()
            .ciphertext().value()).containsExactly(cipher);
    }
}
```

- [x] **Step 3: Run** (Docker)

Run: `./gradlew spotlessApply integrationTest --tests '*RouteMessageIntegrationTest'`
Result: BUILD SUCCESSFUL. Full `./gradlew integrationTest`: BUILD SUCCESSFUL (6 tests, 0 failures).

- [x] **Step 4: Commit**

```bash
git add src/main src/integrationTest ../docs/superpowers/plans/2026-06-10-phase-0-server-skeleton.md
git commit -m "$(printf 'feat(usecase): RouteMessageUseCase persist-then-publish\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 7: Authenticated WebSocket inbound adapter

**Files:**
- Create: `adapter/input/websocket/{WebSocketConfig,WebSocketSessionRegistry,MessageWebSocketHandler,InboundEnvelope}.java`
- Create test: `src/integrationTest/java/com/cloak/server/WebSocketIngestIntegrationTest.java`

> **Implemented (Boot 4.0.6 / Spring Framework 7 reconciliations):** the snippets below reflect the
> committed code. Notes on what was confirmed / changed versus the original draft:
> (a) **`session.getPrincipal()` worked out-of-the-box** — no `HandshakeInterceptor`/`HandshakeHandler`
> adaptation was needed. The resource-server filter chain authenticates the `/ws` HTTP upgrade
> (`anyRequest().authenticated()` from Task 2) and Spring propagates the authenticated
> `JwtAuthenticationToken` straight onto the negotiated `WebSocketSession`, so `sub(session)` casts
> it directly. The handler's `handleTextMessage` was confirmed to fire with a non-null
> `JwtAuthenticationToken` principal (no `ClassCastException`/NPE in logs).
> (b) **`SecurityConfig` was NOT modified** — the original "Files" list mentioned permitting the `/ws`
> path, but the existing `anyRequest().authenticated()` rule is exactly what we want: it *requires*
> auth on the upgrade. The unauthenticated handshake is rejected by that same rule (verified by a
> dedicated test). The `SecurityConfig` line was therefore dropped from the Files list.
> (c) `spring-boot-starter-websocket:4.0.6` resolves cleanly and brings `spring-websocket:7.0.7`; the
> classic `WebSocketConfigurer` / `TextWebSocketHandler` / `@EnableWebSocket` API is unchanged in
> Spring 7, so the handler/config below compiled and ran as written.
> (d) A **second test** (`unauthenticatedClient_isRejected`) was added per Step 4: it omits the bearer
> header and asserts `execute(...).get()` throws (the handshake future completes exceptionally because
> the filter chain returns 401 on the upgrade).
> Full `./gradlew integrationTest` stayed green (6 suites, 8 tests, 0 failures); `./gradlew test`
> (ArchUnit boundary rules) confirms the WS input adapter depends only on use case + domain.

- [x] **Step 1: Add Spring WebSocket dependency**

In `server/build.gradle` `dependencies {}`:
```groovy
implementation 'org.springframework.boot:spring-boot-starter-websocket'
```

- [x] **Step 2: Session registry + inbound envelope + handler**

Create `adapter/input/websocket/InboundEnvelope.java`:
```java
package com.cloak.server.adapter.input.websocket;

/** Matches docs/contracts/phase0-message-envelope.md. */
public record InboundEnvelope(
    String messageId, String toSub, String deviceId, String ciphertext) {}
```
Create `adapter/input/websocket/WebSocketSessionRegistry.java`:
```java
package com.cloak.server.adapter.input.websocket;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
public class WebSocketSessionRegistry {
    private final ConcurrentHashMap<String, Set<WebSocketSession>> bySub = new ConcurrentHashMap<>();

    public void add(String sub, WebSocketSession session) {
        bySub.computeIfAbsent(sub, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void remove(String sub, WebSocketSession session) {
        var set = bySub.get(sub);
        if (set != null) {
            set.remove(session);
        }
    }

    public Set<WebSocketSession> sessionsFor(String sub) {
        return bySub.getOrDefault(sub, Set.of());
    }
}
```
Create `adapter/input/websocket/MessageWebSocketHandler.java`:
```java
package com.cloak.server.adapter.input.websocket;

import com.cloak.server.usecase.RouteMessageCommand;
import com.cloak.server.usecase.RouteMessageUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class MessageWebSocketHandler extends TextWebSocketHandler {
    private final RouteMessageUseCase useCase;
    private final WebSocketSessionRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public MessageWebSocketHandler(RouteMessageUseCase useCase, WebSocketSessionRegistry registry) {
        this.useCase = useCase;
        this.registry = registry;
    }

    private String sub(WebSocketSession session) {
        // getPrincipal() is the JwtAuthenticationToken set by the resource-server-authenticated
        // upgrade; no handshake adaptation needed.
        var auth = (JwtAuthenticationToken) session.getPrincipal();
        return auth.getToken().getSubject();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        registry.add(sub(session), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.remove(sub(session), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        InboundEnvelope env = mapper.readValue(message.getPayload(), InboundEnvelope.class);
        useCase.route(new RouteMessageCommand(
            env.messageId(), sub(session), env.toSub(), env.deviceId(),
            Base64.getDecoder().decode(env.ciphertext())));
    }
}
```
Create `adapter/input/websocket/WebSocketConfig.java`:
```java
package com.cloak.server.adapter.input.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final MessageWebSocketHandler handler;

    public WebSocketConfig(MessageWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws");
    }
}
```
The `/ws` handshake is an HTTP request, so the resource-server filter chain already authenticates it (Task 2 `anyRequest().authenticated()`); `session.getPrincipal()` is the `JwtAuthenticationToken`.

- [x] **Step 3: Failing integration test — authenticated WS ingest persists + publishes (plus an unauthenticated-rejection test)**

Create `src/integrationTest/java/com/cloak/server/WebSocketIngestIntegrationTest.java`:
```java
package com.cloak.server;

import com.cloak.server.port.output.message.MessageRepositoryPort;
import com.cloak.server.domain.message.MessageId;
import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Tokens;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class WebSocketIngestIntegrationTest extends IntegrationTestBase {

    @LocalServerPort int port;
    @Autowired MessageRepositoryPort repository;

    @Test
    void authenticatedClient_sendsEnvelope_serverPersists() throws Exception {
        String token = Tokens.accessToken(issuerUri(), "alice");
        var headers = new WebSocketHttpHeaders();
        headers.setBearerAuth(token);

        var session = new StandardWebSocketClient()
            .execute(new TextWebSocketHandler() {}, headers,
                URI.create("ws://localhost:" + port + "/ws")).get();

        String id = "66666666-6666-6666-6666-666666666666";
        String ciphertext = Base64.getEncoder().encodeToString(new byte[] {5, 5, 5});
        session.sendMessage(new TextMessage("""
            {"messageId":"%s","toSub":"bob-sub","deviceId":null,"ciphertext":"%s"}
            """.formatted(id, ciphertext)));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(repository.find(new MessageId(id))).isPresent());
        session.close();
    }

    @Test
    void unauthenticatedClient_isRejected() {
        // No bearer header: the resource-server filter chain rejects the upgrade, so the handshake
        // future completes exceptionally.
        assertThatThrownBy(() ->
            new StandardWebSocketClient()
                .execute(new TextWebSocketHandler() {}, new WebSocketHttpHeaders(),
                    URI.create("ws://localhost:" + port + "/ws")).get())
            .isInstanceOf(Exception.class);
    }
}
```
(Imports add `org.assertj.core.api.Assertions.assertThatThrownBy` and
`org.springframework.web.socket.WebSocketHttpHeaders`.)

- [x] **Step 4: Run** (Docker)

Run: `./gradlew spotlessApply integrationTest --tests '*WebSocketIngestIntegrationTest'`
Result: BUILD SUCCESSFUL — both tests PASS (authenticated WS message persisted; unauthenticated
handshake rejected). Full `./gradlew integrationTest`: BUILD SUCCESSFUL (6 suites, 8 tests, 0
failures). `./gradlew test` (ArchUnit) green: the WS input adapter depends only on use case + domain.
Did NOT run `./gradlew check` (JaCoCo Java-25 gate is Task 8).

- [x] **Step 5: Commit**

```bash
git add build.gradle src/main src/integrationTest ../docs/superpowers/plans/2026-06-10-phase-0-server-skeleton.md
git commit -m "$(printf 'feat(ws): authenticated WebSocket ingest -> RouteMessageUseCase\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 8: Outbound consumer + full round-trip delivery

**Files:**
- Create: `adapter/input/kafka/OutboundMessageConsumer.java`
- Create test: `src/integrationTest/java/com/cloak/server/RoundTripIntegrationTest.java`

- [ ] **Step 1: Consumer that delivers to the recipient's sessions**

Create `adapter/input/kafka/OutboundMessageConsumer.java`:
```java
package com.cloak.server.adapter.input.kafka;

import com.cloak.server.adapter.input.websocket.WebSocketSessionRegistry;
import com.cloak.server.adapter.output.kafka.message.OutboundEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class OutboundMessageConsumer {
    private final WebSocketSessionRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public OutboundMessageConsumer(WebSocketSessionRegistry registry) {
        this.registry = registry;
    }

    @KafkaListener(topics = "cloak.messages.outbound", groupId = "cloak-server")
    public void onOutbound(OutboundEnvelope env) throws Exception {
        String toSub = env.getToSub().toString();
        var buf = env.getCiphertext();
        byte[] ct = new byte[buf.remaining()];
        buf.duplicate().get(ct);
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("messageId", env.getMessageId().toString());
        payload.put("toSub", toSub);
        payload.put("fromSub", env.getFromSub().toString());
        payload.put("deviceId", env.getDeviceId() == null ? null : env.getDeviceId().toString());
        payload.put("ciphertext", java.util.Base64.getEncoder().encodeToString(ct));
        String json = mapper.writeValueAsString(payload); // forward ciphertext envelope unchanged
        for (WebSocketSession session : registry.sessionsFor(toSub)) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        }
    }
}
```

- [ ] **Step 2: Failing round-trip test (the skeleton's crown)**

Create `src/integrationTest/java/com/cloak/server/RoundTripIntegrationTest.java`:
```java
package com.cloak.server;

import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Tokens;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import static org.assertj.core.api.Assertions.assertThat;

class RoundTripIntegrationTest extends IntegrationTestBase {

    @LocalServerPort int port;

    @Test
    void aliceToBob_deliveredOverWebSocket_ciphertextUnchanged() throws Exception {
        // bob connects and waits for a delivery
        var received = new CompletableFuture<String>();
        var bobToken = Tokens.accessToken(issuerUri(), "bob");
        var bobHeaders = new WebSocketHttpHeaders();
        bobHeaders.setBearerAuth(bobToken);
        var bob = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override protected void handleTextMessage(WebSocketSession s, TextMessage m) {
                received.complete(m.getPayload());
            }
        }, bobHeaders, URI.create("ws://localhost:" + port + "/ws")).get();

        // NOTE: bob's `sub` is the Keycloak subject for "bob"; the envelope below
        // must target that sub. Resolve it from a minted token if needed.
        String bobSub = Tokens.subject(bobToken);

        // alice connects and sends to bob
        var aliceHeaders = new WebSocketHttpHeaders();
        aliceHeaders.setBearerAuth(Tokens.accessToken(issuerUri(), "alice"));
        var alice = new StandardWebSocketClient()
            .execute(new TextWebSocketHandler() {}, aliceHeaders,
                URI.create("ws://localhost:" + port + "/ws")).get();

        byte[] cipher = {9, 0, 2, 1, 0};
        String b64 = Base64.getEncoder().encodeToString(cipher);
        alice.sendMessage(new TextMessage("""
            {"messageId":"77777777-7777-7777-7777-777777777777","toSub":"%s","deviceId":null,"ciphertext":"%s"}
            """.formatted(bobSub, b64)));

        String delivered = received.get(15, TimeUnit.SECONDS);
        assertThat(delivered).contains(b64);          // ciphertext forwarded unchanged
        assertThat(delivered).doesNotContain("plaintext");
        alice.close();
        bob.close();
    }
}
```
Add a `subject(...)` helper to `Tokens.java` that decodes the JWT `sub` claim:
```java
public static String subject(String accessToken) {
    try {
        String payload = new String(java.util.Base64.getUrlDecoder()
            .decode(accessToken.split("\\.")[1]));
        return new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(payload).get("sub").asText();
    } catch (Exception e) {
        throw new IllegalStateException("decode sub failed", e);
    }
}
```

- [x] **Step 3: Run** (Docker)

Run: `./gradlew spotlessApply integrationTest --tests '*RoundTripIntegrationTest'`
Expected: PASS — alice's ciphertext envelope is persisted, published to Kafka keyed by bob's sub, consumed, and delivered to bob's WebSocket session **unchanged**. (PASSED.)

- [x] **Step 4: Run the full gate**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL (all gates, ≥90% coverage). (PASSED — see notes below.)

- [x] **Step 5: Commit**

```bash
git add build.gradle src/main src/integrationTest config/checkstyle \
  ../docs/superpowers/plans/2026-06-10-phase-0-server-skeleton.md
git commit -m "$(printf 'feat(delivery): Kafka consumer fans envelope to recipient WS session\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

#### Implementation notes (as built) — divergences from the plan above

The consumer was created at `adapter/input/kafka/OutboundMessageConsumer.java` (input adapter),
matching the existing package convention (`adapter/input/websocket`, `adapter/output/kafka/message`)
rather than a bare `adapter/input/kafka`. The Avro getters return `String`/`ByteBuffer` (the schema
sets `avro.java.string=String`), so the `.toString()` calls on the snippet are no-ops but kept for
robustness. `RoundTripIntegrationTest` builds the JSON frame from a concatenated format string (not a
text block) to stay under the 100-char line-length rule. `Tokens.subject(...)` reuses the class's
static `MAPPER`.

**JaCoCo (the Java-25 blocker).** Bumped `jacoco.toolVersion` `0.8.12 → 0.8.13` in `build.gradle`.
0.8.13 is the first JaCoCo release that reads Java 25 bytecode (class file major version 69); on
0.8.12 the coverage tasks errored with `Unsupported class file major version 69` once real app
classes existed. After the bump, `jacocoTestReport` and `jacocoTestCoverageVerification` run cleanly
(no major-version error). It is the newest released version on Maven Central, so no risk of a
still-unsupported gap.

**Two additional pre-existing gate blockers surfaced here (Tasks 1–7 never ran a full `check`).**
1. *Checkstyle linted the generated Avro source.* `checkstyleMain`/`checkstyleIntegrationTest` failed
   on `build/generated-main-avro-java/.../OutboundEnvelope.java`. Fix: drop the generated source tree
   from every `Checkstyle` task in `build.gradle`
   (`source = source.filter { !it.path.contains('generated-main-avro-java') }`). Generated code is
   already excluded from the coverage denominator; this aligns the lint scope. *(Not a broadened
   coverage exclusion — Checkstyle only.)*
2. *Hand-written merged code carried `MissingJavadoc*` / `AbbreviationAsWordInName` warnings* under
   `maxWarnings = 0`. Fix: added concise Javadoc to the public types/methods of `Message`,
   `MessageId`, `Ciphertext`, `MessagePublisherPort`, `MessageRepositoryPort`, `RouteMessageUseCase`,
   `RouteMessageCommand`, `EncryptedMessageEntity`, `WebSocketSessionRegistry`, `WebSocketConfig`,
   `MessageWebSocketHandler`, plus the new `OutboundMessageConsumer`; and added a narrow
   `AbbreviationAsWordInName` suppression for the intentionally-named `WhoAmI*` types in
   `config/checkstyle/checkstyle-suppressions.xml` (rename would be unnatural).

**Coverage result:** `jacocoTestCoverageVerification` passes; measured INSTRUCTION coverage **98.32%**
(covered 526 / missed 9). The only uncovered lines are the non-null `deviceId` branch in
`MessageRowMapper` and the `session.isOpen()`-false / no-sessions edge in the consumer — both well
within the 90% gate, so no extra coverage-only tests were added (avoiding device-row FK seeding that
the gate did not require).

**Final gate:** `./gradlew check` → **BUILD SUCCESSFUL**. No ciphertext/plaintext is logged anywhere
(verified by grep; the kafka adapters carry no logger and only re-encode the opaque blob as base64 to
forward it).

---

### Task 9: Docs — README + contract sync

**Files:**
- Modify: `server/README.md`, `docs/contracts/phase0-message-envelope.md`

> **Implemented:** the plan draft underspecified the contract reconciliation. The original
> contract showed a single frame containing `fromSub`, but the implementation uses two distinct
> frames. The reconciliation is documented here as built.

- [x] **Step 1: Add `## API` section to `server/README.md`**

Added an `## API` section documenting:
- `GET /v1/me` — returns the caller's Keycloak `sub`; requires `Authorization: Bearer <token>`;
  returns 401 on missing/invalid token.
- `/ws` WebSocket — requires `Authorization: Bearer <Keycloak access token>` at the HTTP
  upgrade; unauthenticated upgrades are rejected 401. Describes inbound frame (no `fromSub`,
  sender derived from JWT `sub`), server behaviour (persist + Kafka publish keyed by recipient
  `sub`), and delivery frame (`fromSub` server-stamped). Auth model note: JWKS-validated
  Keycloak tokens (issuer + `cloak-api` audience); local-dev `alice`/`bob` (password `password`).

- [x] **Step 2: Reconcile `docs/contracts/phase0-message-envelope.md` to implementation**

The original contract had a single frame containing `fromSub`. The implementation uses two
frames. Reconciliation made:

1. Replaced the single "Frame" section with **two** labelled frames:
   - **Inbound frame (client → server, over `/ws`):** `{ messageId, toSub, deviceId,
     ciphertext(base64) }` — **no `fromSub`**. Prose explains the sender is derived from the
     authenticated JWT `sub` by `MessageWebSocketHandler`, structurally preventing spoofing.
   - **Delivery frame (server → recipient, over `/ws`):** `{ messageId, toSub, fromSub,
     deviceId, ciphertext(base64) }` — `fromSub` is the server-stamped sender `sub` (not
     client-supplied), sourced from the Avro `OutboundEnvelope`.
2. Split **Cleartext-field justification** into two subsections (one per frame).
3. Updated **Server trust rules**: sender spoofing is now structurally prevented (the inbound
   frame carries no `fromSub`; the server sets `sender_sub`/delivery `fromSub` from the
   validated JWT). The `deviceId` ownership rule is retained, with an honest note that
   **Phase 0 only enforces the FK referential check — ownership validation (device belongs to
   sender) is pending a later slice**.
4. Preserved the "Known gaps / recipient discovery" section unchanged.

- [x] **Step 3: Commit**

```bash
git add server/README.md docs/contracts/phase0-message-envelope.md \
  docs/superpowers/plans/2026-06-10-phase-0-server-skeleton.md
git commit -m "$(printf 'docs: document /ws + auth; sync Phase 0 envelope contract to inbound/delivery frames\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

## Definition of Done (this plan)

- `./gradlew check` green: all gates pass, coverage ≥90% (predominantly integration tests).
- An authenticated WebSocket client sends an opaque ciphertext envelope; the server **persists it byte-for-byte**, publishes it to `cloak.messages.outbound` keyed by recipient `sub`, and a consumer **delivers it unchanged** to the recipient's connected session.
- Unauthenticated handshakes are rejected; `/v1/me` requires a valid Keycloak token.
- No plaintext appears in any DB column, log line, or Kafka record (asserted).
- `server/README.md` + `docs/contracts/` reflect the implemented contract.

## Spec coverage (roadmap Phase 0, server portion)

- ✅ JWKS auth (issuer + audience), validated against real Keycloak tokens.
- ✅ Authenticated WebSocket → persist ciphertext → Kafka publish (keyed by recipient) → consumer delivery.
- ✅ Seed users in `cloak-realm.json`; realm imported into the test harness.
- ✅ Privacy assertions (ciphertext unchanged; no plaintext).
- ⏭️ iOS client (login, libsignal keygen, send/receive vs mocked Service) = **Plan 3**.

## Deferred — persistence performance tuning

The persistence adapter (Task 4) uses Spring Data JPA (Hibernate-generated SQL) for `save` and
`findById` — correct and sufficient for these insert/PK-lookup operations. The hexagonal
`MessageRepositoryPort` preserves the freedom to drop to hand-tuned SQL per operation later with
zero domain/use-case blast radius (`ARCHITECTURE_GUIDE` §2.4 ISP: read projections belong on a
separate `…QueryPort`). Two concrete candidates to revisit **when real query/throughput load
arrives — measure first, don't pre-optimise the skeleton**:

- ⏭️ **Assigned-id insert does a SELECT-before-INSERT.** `EncryptedMessageEntity` has a manually
  assigned UUID `@Id`, so Spring Data `save()` treats it as non-new and calls `merge()` → a
  redundant `SELECT` before every `INSERT` on the message write path. When ingest throughput
  matters, fix it behind the port: implement `Persistable<UUID>.isNew()`, or use
  `EntityManager.persist`, or a native / `JdbcClient` insert.
- ⏭️ **Tuned reads via a dedicated `MessageQueryPort`.** First real candidate is the offline-delivery
  fetch — "undelivered messages for `recipient_sub`, ordered by `created_at`, paginated" — which
  already has `idx_message_recipient (recipient_sub, created_at)`. Implement as a tuned native
  `@Query` / `JdbcClient` / jOOQ read behind a separate query port. Select only the columns needed
  (don't drag `ciphertext` into a routing-metadata scan); never log opaque-byte query params.

## Deferred — Kafka topic configuration ownership

- ⏭️ **Topic config may move out of the server.** `cloak.messages.outbound` is currently declared in
  two places: `queue/create-topics.sh` (the source of truth per `queue/CLAUDE.md` §4) and the Spring
  `NewTopic` bean in `common/config/KafkaTopicsConfig.java` (auto-created by `KafkaAdmin` at startup).
  In future the topology may be owned entirely outside the server — by `create-topics.sh`,
  infra-as-code, or a platform/topic-provisioning tool managed per environment. At that point
  **`KafkaTopicsConfig` becomes redundant and can be removed**: the server would only produce/consume,
  not declare topics. (Until then, the bean is convenient for local bring-up and tests; keep the two
  definitions in sync — partition count / RF.)

## Added beyond original scope — standard API response envelope

Added after the plan's core tasks (decided with the user): a standard REST response contract so success
and error payloads are uniform. See `ARCHITECTURE_GUIDE` §9 for the authoritative shape.

- **Envelope** `WrappedResponse<T>` = `{ data, errors, traceId }`; `errors` is `null` on success and a
  **non-empty array** of `{ code, message, field? }` on failure (one element per field for validation).
- **`traceId`** on every response body and as the **`X-Trace-Id`** header, sourced from a minimal
  `CorrelationFilter` (a partial down-payment on §10.1 — the full observability stack is its own plan).
- Errors emitted in two places, one envelope: a `@RestControllerAdvice` (domain + framework exceptions)
  and Spring Security's `AuthenticationEntryPoint`/`AccessDeniedHandler` (401/403, raised before the
  dispatcher). Applied to `/v1/me`; all future REST endpoints inherit it.
- ⏭️ **WebSocket error frames are deferred.** `/ws` is a stream, not request/response, so a standard
  error frame (malformed inbound frame, mid-session auth failure, rejected message) is a separate,
  smaller follow-up — not covered by this REST envelope.
- ✅ **Standardised on Jackson 3.** Spring Boot 4's default JSON stack is **Jackson 3**
  (`tools.jackson.databind.json.JsonMapper`) — there is no Jackson 2 `ObjectMapper` bean. All
  application JSON now uses the injected Jackson 3 `JsonMapper`: the REST envelope, and (migrated from
  `new ObjectMapper()`) the WebSocket handler and Kafka consumer. No `com.fasterxml.jackson.databind`
  reference remains in `src/main` (Jackson 2 lingers on the classpath only transitively via the
  Confluent/Kafka deps; annotations under `com.fasterxml.jackson.annotation` are unaffected).

## Deferred — code-review follow-ups (PR #4)

From the `/code-review` pass. **Fixed in-PR:** per-session delivery isolation (one dead socket no longer
aborts the others or triggers redelivery storms), session-registry empty-key leak, inbound trace-id
length/charset validation, and a defensive WS principal guard. **Deferred:**

- ⏭️ **Transactional outbox for delivery.** `RouteMessageUseCase` commits the DB write, then publishes to
  Kafka; if the publish throws after commit, the message is persisted but never delivered (no retry).
  Add an outbox (persist + enqueue atomically, relay asynchronously) when delivery reliability is built.
- ⏭️ **Map controller-thrown security/status exceptions.** `GlobalExceptionHandler`'s catch-all turns an
  `AccessDeniedException` / `ResponseStatusException` raised in the dispatcher into 500 INTERNAL_ERROR;
  add explicit handlers (AccessDeniedException → 403; honour ResponseStatusException) before method
  security (`@PreAuthorize`) lands.
- ⏭️ **Typed delivery frame.** `OutboundMessageConsumer` rebuilds the delivery JSON as ad-hoc map keys,
  duplicating the wire shape `InboundEnvelope` / the contract doc already define; a typed `DeliveryFrame`
  record would be compiler-checked against drift. Pairs naturally with the WS error-frame work.
```
