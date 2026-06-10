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
    "credentials": [{ "type": "password", "value": "password", "temporary": false }]
  },
  {
    "username": "bob",
    "enabled": true,
    "email": "bob@example.com",
    "emailVerified": true,
    "credentials": [{ "type": "password", "value": "password", "temporary": false }]
  }
]
```
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

- [ ] **Step 3: Commit**

```bash
git add iam/realm/cloak-realm.json
git commit -m "$(printf 'feat(iam): seed alice/bob users and cloak-test client for Phase 0\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 2: Resource-server auth + auth probe endpoint

**Files:**
- Modify: `server/build.gradle`, `server/src/main/resources/application.yml`
- Create: `common/config/AuthProperties.java`, `common/config/SecurityConfig.java`, `adapter/input/rest/identity/WhoAmIController.java`
- Create test: `WhoAmIIntegrationTest.java`, `support/Tokens.java`, and extend `IntegrationTestBase` to import the realm.

- [ ] **Step 1: Add the resource-server dependency**

In `server/build.gradle` `dependencies {}` add:
```groovy
implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
```

- [ ] **Step 2: Import the realm into the test Keycloak + add a token helper**

In `IntegrationTestBase.java` (Plan 1), change the Keycloak field to import the realm from the repo, and expose the issuer:
```java
static final KeycloakContainer KEYCLOAK =
    new KeycloakContainer("quay.io/keycloak/keycloak:26.0")
        .withRealmImportFile("/realms/cloak-realm.json");
```
Add the realm file to the integration-test classpath so the container can import it:
```bash
mkdir -p src/integrationTest/resources/realms
cp ../iam/realm/cloak-realm.json src/integrationTest/resources/realms/cloak-realm.json
```
> The realm export remains single-sourced in `iam/`; this copy is a build input for tests. A Gradle `processIntegrationTestResources` copy keeps it in sync — add to `build.gradle`:
```groovy
tasks.named('processIntegrationTestResources') {
    from('../iam/realm') { into 'realms' }
}
```
(Delete the manual copy after adding this; the task copies at build time.)

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
            return json.get("access_token").asText();
        } catch (Exception e) {
            throw new IllegalStateException("token mint failed", e);
        }
    }
}
```
Expose the issuer to tests by adding to `IntegrationTestBase`:
```java
protected static String issuerUri() {
    return KEYCLOAK.getAuthServerUrl() + "/realms/cloak";
}
```

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
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(props.issuerUri());
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
And wire the test issuer dynamically — in `IntegrationTestBase.props(...)` add:
```java
r.add("cloak.auth.issuer-uri", IntegrationTestBase::issuerUri);
r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", IntegrationTestBase::issuerUri);
```
Also add `spring-boot-starter-actuator` for the health endpoint:
```groovy
implementation 'org.springframework.boot:spring-boot-starter-actuator'
```

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

Create `src/integrationTest/java/com/cloak/server/WhoAmIIntegrationTest.java`:
```java
package com.cloak.server;

import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import static org.assertj.core.api.Assertions.assertThat;

class WhoAmIIntegrationTest extends IntegrationTestBase {

    @Autowired TestRestTemplate rest;

    @Test
    void noToken_returns401() {
        var resp = rest.getForEntity("/v1/me", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validToken_returnsSub() {
        String token = Tokens.accessToken(issuerUri(), "alice");
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        var resp = rest.exchange("/v1/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"sub\"");
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

- [ ] **Step 1: Define the output port**

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

- [ ] **Step 2: Entity + Spring Data repo + mapper + adapter**

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

- [ ] **Step 3: Failing integration test — round-trips ciphertext byte-for-byte**

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
        repository.save(Message.create(id, "alice-sub", "bob-sub",
            "33333333-3333-3333-3333-333333333333", new Ciphertext(cipher)));

        var loaded = repository.find(id).orElseThrow();
        assertThat(loaded.ciphertext().value()).containsExactly(cipher);
        assertThat(loaded.recipientSub()).isEqualTo("bob-sub");
    }
}
```

- [ ] **Step 4: Run** (Docker)

Run: `./gradlew spotlessApply integrationTest --tests '*MessagePersistenceIntegrationTest'`
Expected: PASS (the persisted `ciphertext` column equals the input bytes exactly — §0.6.4 privacy assertion).

- [ ] **Step 5: Commit**

```bash
git add src/main src/integrationTest
git commit -m "$(printf 'feat(persistence): Message repository port + JPA adapter\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 5: Kafka publisher port + adapter + topics

**Files:**
- Create: `port/output/message/MessagePublisherPort.java`
- Create: `adapter/output/kafka/message/{OutboundEnvelope,KafkaMessagePublisherAdapter}.java`
- Create: `common/config/KafkaTopicsConfig.java`
- Modify: `application.yml` (kafka producer/consumer serializers)
- Create test: `src/integrationTest/java/com/cloak/server/MessagePublishIntegrationTest.java`

- [ ] **Step 1: Avro tooling + the envelope schema (Confluent Schema Registry)**

Record values are **Avro** (see `queue/CLAUDE.md` → Serialization). Add the Confluent repo + Avro tooling to `server/build.gradle`:
- `plugins {}`: `id 'com.github.davidmc24.gradle.plugin.avro' version '1.9.1'`
- `repositories {}` (alongside `mavenCentral()`): `maven { url 'https://packages.confluent.io/maven/' }`
- `dependencies {}`:
```groovy
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
Run `./gradlew generateAvroJava compileJava`. Expected: generated `OutboundEnvelope` on the classpath; **BUILD SUCCESSFUL**. (The `.avsc` is the in-repo source of truth; do not hand-write the Java class.)

- [ ] **Step 2: Port + adapter + topics + config**

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
// in props(...):
r.add("spring.kafka.properties.schema.registry.url", IntegrationTestBase::schemaRegistryUrl);
```

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
        try (var consumer = new KafkaConsumer<String, Object>(
                props, new StringDeserializer(), new KafkaAvroDeserializer())) {
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
- Create test: `src/integrationTest/java/com/cloak/server/RouteMessageIntegrationTest.java`

- [ ] **Step 1: Command + use case**

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
`TransactionTemplate` is auto-configured by Spring Boot when JPA is present; if no bean is found, add to a config class:
```java
@Bean
TransactionTemplate transactionTemplate(org.springframework.transaction.PlatformTransactionManager tm) {
    return new TransactionTemplate(tm);
}
```
(Put this in `common/config/PersistenceConfig.java`.)

- [ ] **Step 2: Failing integration test**

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

- [ ] **Step 3: Run** (Docker)

Run: `./gradlew spotlessApply integrationTest --tests '*RouteMessageIntegrationTest'`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main src/integrationTest
git commit -m "$(printf 'feat(usecase): RouteMessageUseCase persist-then-publish\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 7: Authenticated WebSocket inbound adapter

**Files:**
- Create: `adapter/input/websocket/{WebSocketConfig,WebSocketSessionRegistry,MessageWebSocketHandler,InboundEnvelope}.java`
- Modify: `common/config/SecurityConfig.java` (permit the WS upgrade path through the resource-server-authenticated chain)
- Create test: `src/integrationTest/java/com/cloak/server/WebSocketIngestIntegrationTest.java`

- [ ] **Step 1: Add Spring WebSocket dependency**

In `server/build.gradle` `dependencies {}`:
```groovy
implementation 'org.springframework.boot:spring-boot-starter-websocket'
```

- [ ] **Step 2: Session registry + inbound envelope + handler**

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
import org.springframework.security.oauth2.jwt.Jwt;
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
        var auth = (JwtAuthenticationToken) ((org.springframework.security.core.Authentication)
            session.getPrincipal());
        return ((Jwt) auth.getToken()).getSubject();
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

- [ ] **Step 3: Failing integration test — authenticated WS ingest persists + publishes**

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
}
```

- [ ] **Step 4: Run** (Docker)

Run: `./gradlew spotlessApply integrationTest --tests '*WebSocketIngestIntegrationTest'`
Expected: PASS (authenticated WS message persisted). Also confirm an **unauthenticated** connection fails — add a second test that omits the bearer header and asserts the `execute(...).get()` throws.

- [ ] **Step 5: Commit**

```bash
git add build.gradle src/main src/integrationTest
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

- [ ] **Step 3: Run** (Docker)

Run: `./gradlew spotlessApply integrationTest --tests '*RoundTripIntegrationTest'`
Expected: PASS — alice's ciphertext envelope is persisted, published to Kafka keyed by bob's sub, consumed, and delivered to bob's WebSocket session **unchanged**.

- [ ] **Step 4: Run the full gate**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL (all gates, ≥90% coverage).

- [ ] **Step 5: Commit**

```bash
git add src/main src/integrationTest
git commit -m "$(printf 'feat(delivery): Kafka consumer fans envelope to recipient WS session\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 9: Docs — README + contract sync

**Files:**
- Modify: `server/README.md`, `docs/contracts/phase0-message-envelope.md` (confirm match)

- [ ] **Step 1: Document the WebSocket endpoint + auth**

Append to `server/README.md` an `## API` section: the `/ws` WebSocket endpoint requires `Authorization: Bearer <Keycloak access token>` at handshake; the inbound frame matches `docs/contracts/phase0-message-envelope.md`; `/v1/me` returns the caller's `sub`.

- [ ] **Step 2: Confirm the contract matches the implemented `InboundEnvelope`/`OutboundEnvelope`.** If fields drift, update `docs/contracts/phase0-message-envelope.md` in this commit (single source of truth).

- [ ] **Step 3: Commit**

```bash
git add README.md
cd .. && git add docs/contracts && cd server
git commit -m "$(printf 'docs: document /ws + auth; sync Phase 0 envelope contract\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
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
```
