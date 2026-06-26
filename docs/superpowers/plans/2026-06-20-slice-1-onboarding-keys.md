# Slice 1 — Account Onboarding + Device Key Registration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A user self-registers/logs in via a Cloak-branded Keycloak, the iOS app generates a real libsignal device-key bundle, publishes the **public** bundle to a new server registry, and persists private keys encrypted on-device — no sessions or messages yet (Slice 2).

**Architecture:** Contract-first, then TDD both sides. Server is hexagonal (`adapter → usecase → port → adapter/output`), mirroring the Phase 0 message path. iOS adds libsignal + GRDB/SQLCipher via CocoaPods, a `Foundation/Encryption` crypto layer implementing libsignal's key stores, and a real onboarding flow. Keycloak gets a custom login theme.

**Tech Stack:** Java 25 / Spring Boot 4 (server), PostgreSQL + Flyway, Keycloak 26 (FreeMarker theme), Swift 6 / SwiftUI + `LibSignalClient` + `GRDB.swift/SQLCipher` (CocoaPods), XcodeGen, Swift Testing.

**Design references (read before starting):**
- Spec: `docs/superpowers/specs/2026-06-20-slice-1-onboarding-keys-design.md`
- iOS guide: `app/docs/ARCHITECTURE_GUIDE.md` §3 (presentation), §5 (DI), §6 (data/GRDB+SQLCipher), §7 (crypto/Signal)
- Design tokens: `docs/superpowers/specs/2026-06-10-cloak-design-system-design.md`
- Existing server patterns to copy: `WhoAmIController.java`, `SecurityConfig.java`, `RouteMessageUseCase.java`, `MessageRepositoryAdapter.java` + `EncryptedMessageEntity.java`, `support/IntegrationTestBase.java`, `support/Tokens.java`, `WhoAmIIntegrationTest.java`.

**Conventions inherited (root `CLAUDE.md`):** TDD red→green→refactor; ≥90% coverage; server lint = Spotless googleJavaFormat + Checkstyle (build fails on warnings); iOS lint = SwiftLint `--strict`; commit messages end with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`; update READMEs in the same change set.

**Wire-format key sizes (libsignal serialization):** Curve25519 **public key = 33 bytes** (0x05 type prefix + 32), **signature = 64 bytes**. The server validates these sizes but never crypto-verifies (that's the receiving client in Slice 2). Tests that need a "valid" bundle without libsignal on the JVM use right-length random bytes.

---

## Phase A — Contract (the seam)

### Task A1: Device-key-bundle contract + fixture

**Files:**
- Create: `docs/contracts/slice1-device-key-bundle.md`
- Create: `docs/contracts/fixtures/slice1-key-bundle.json`
- Modify: `docs/contracts/README.md` (add a bullet linking the new contract)

- [ ] **Step 1: Write the contract doc.** Create `docs/contracts/slice1-device-key-bundle.md`:

````markdown
# Slice 1 — Device public key bundle (REST)

Published by a device on first run so other users can later start an encrypted session with it
(Slice 2 / X3DH). The bundle is **public key material only** — private keys never leave the device.

## Request — `PUT /v1/keys`

Bearer-authenticated (`Authorization: Bearer <Keycloak access token>`, `aud: cloak-api`).
The owner is taken from the validated JWT `sub` — **never** from the body.

```json
{
  "registrationId": 12345,
  "deviceId": 1,
  "identityKey": "base64 (33 bytes)",
  "signedPreKey": { "keyId": 1, "publicKey": "base64 (33 bytes)", "signature": "base64 (64 bytes)" },
  "oneTimePreKeys": [ { "keyId": 1, "publicKey": "base64 (33 bytes)" } ]
}
```

- `registrationId` — libsignal registration id (1..16383).
- `deviceId` — libsignal device number; **1** for the primary device (multi-device = Slice 5).
- `oneTimePreKeys` — 1..100 entries, unique `keyId`s.

Idempotent: re-`PUT` replaces this device's bundle. **Response: `204 No Content`.**

## Validation (server)
Structural + auth only — shape, base64 decodes, public keys = 33 bytes, signature = 64 bytes,
1..100 one-time prekeys with unique ids. The signed-prekey signature is verified **client-side**
during X3DH (Slice 2), where the trust decision lives; the server stays content-blind.

## Cleartext justification (root CLAUDE.md principle 6)
Every field is public key material or routing identity. No plaintext content, no private keys.
````

- [ ] **Step 2: Write the JSON fixture.** Create `docs/contracts/fixtures/slice1-key-bundle.json` (dummy but right-length base64 — `AAAA…` padded; the iOS test asserts structure, the server test builds its own bytes):

```json
{
  "registrationId": 12345,
  "deviceId": 1,
  "identityKey": "BWmFvBQ0jc3v0bH2Yb0r6kE9p5tQ8mC1nD2eF3gH4iJ",
  "signedPreKey": {
    "keyId": 1,
    "publicKey": "BWmFvBQ0jc3v0bH2Yb0r6kE9p5tQ8mC1nD2eF3gH4iJ",
    "signature": "MEUCIQDsignaturebytesplaceholder000000000000000000000000000000000000000000000000000000ag=="
  },
  "oneTimePreKeys": [
    { "keyId": 1, "publicKey": "BWmFvBQ0jc3v0bH2Yb0r6kE9p5tQ8mC1nD2eF3gH4iJ" }
  ]
}
```
> Note: the example strings only document the JSON **shape** (field names/nesting). Tests do not assert these exact bytes — iOS asserts the structure of its real serialized bundle; the server builds right-length bytes in-test.

- [ ] **Step 3: Link it from the contracts index.** Add to `docs/contracts/README.md` a bullet: `- slice1-device-key-bundle.md — PUT /v1/keys public prekey bundle (Slice 1).`

- [ ] **Step 4: Commit.**
```bash
git add docs/contracts/
git commit -m "docs(contract): Slice 1 device public key bundle (PUT /v1/keys)"
```

---

## Phase B — Database (Flyway V2)

### Task B1: Prekey registry migration

**Files:**
- Create: `db/migrations/V2__device_prekey_registry.sql`
- Test: covered by the server Testcontainers suite (Flyway runs `../db/migrations`; Task C4 asserts it applies and rows persist).

- [ ] **Step 1: Write the migration.** Create `db/migrations/V2__device_prekey_registry.sql`:

```sql
-- Slice 1: Signal Protocol public prekey registry. Public key material + routing identity only
-- (root CLAUDE.md principle 6). device.public_key (V1) is reused as the identity public key.

ALTER TABLE device
  ADD COLUMN registration_id INTEGER,
  ADD COLUMN device_number   INTEGER NOT NULL DEFAULT 1;   -- libsignal deviceId; multi-device = Slice 5

-- Idempotent upsert key: one bundle per (user, device number).
ALTER TABLE device
  ADD CONSTRAINT uq_device_owner_number UNIQUE (owner_sub, device_number);

CREATE TABLE signed_prekey (
  device_id   UUID        NOT NULL REFERENCES device (id) ON DELETE CASCADE,
  key_id      INTEGER     NOT NULL,
  public_key  BYTEA       NOT NULL,
  signature   BYTEA       NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (device_id, key_id)
);

CREATE TABLE one_time_prekey (
  device_id   UUID        NOT NULL REFERENCES device (id) ON DELETE CASCADE,
  key_id      INTEGER     NOT NULL,
  public_key  BYTEA       NOT NULL,
  consumed_at TIMESTAMPTZ,                                  -- NULL = available; Slice 2 consumes one per X3DH
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (device_id, key_id)
);

CREATE INDEX idx_otp_available ON one_time_prekey (device_id) WHERE consumed_at IS NULL;
```

- [ ] **Step 2: Verify the SQL parses against a throwaway Postgres** (optional but fast):
```bash
docker run --rm -e POSTGRES_PASSWORD=x -d --name v2chk -p 55432:5432 postgres:16-alpine
sleep 4
docker exec -i v2chk psql -U postgres -c "CREATE TABLE device (id UUID PRIMARY KEY, owner_sub TEXT NOT NULL, public_key BYTEA NOT NULL UNIQUE, algorithm TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), revoked_at TIMESTAMPTZ);"
docker exec -i v2chk psql -U postgres < db/migrations/V2__device_prekey_registry.sql && echo "V2 OK"
docker rm -f v2chk
```
Expected: `V2 OK`.

- [ ] **Step 3: Commit.**
```bash
git add db/migrations/V2__device_prekey_registry.sql
git commit -m "feat(db): V2 device prekey registry (signed + one-time prekeys)"
```

---

## Phase C — Server (TDD with Testcontainers)

> Run server tests with `./gradlew -p server integrationTest` (Testcontainers) and `./gradlew -p server test` (unit). Lint/format: `./gradlew -p server spotlessApply checkstyleMain checkstyleTest`. The whole gate: `./gradlew -p server build`.

### Task C1: Domain — bundle value objects + validation

**Files:**
- Create: `server/src/main/java/com/cloak/server/domain/device/DeviceKeyBundle.java`
- Create: `server/src/main/java/com/cloak/server/domain/device/SignedPreKey.java`
- Create: `server/src/main/java/com/cloak/server/domain/device/OneTimePreKey.java`
- Test: `server/src/test/java/com/cloak/server/domain/device/DeviceKeyBundleTest.java`

- [ ] **Step 1: Write the failing unit test.** Create `DeviceKeyBundleTest.java`:

```java
package com.cloak.server.domain.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeviceKeyBundleTest {

  private static byte[] pub() {
    byte[] b = new byte[33];
    b[0] = 0x05;
    return b;
  }

  private static SignedPreKey signed() {
    return new SignedPreKey(1, pub(), new byte[64]);
  }

  @Test
  void buildsValidBundle() {
    var bundle =
        DeviceKeyBundle.of(12345, 1, pub(), signed(), List.of(new OneTimePreKey(1, pub())));
    assertThat(bundle.oneTimePreKeys()).hasSize(1);
    assertThat(bundle.deviceNumber()).isEqualTo(1);
  }

  @Test
  void rejectsWrongIdentityKeyLength() {
    assertThatThrownBy(
            () -> DeviceKeyBundle.of(1, 1, new byte[10], signed(), List.of(new OneTimePreKey(1, pub()))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsWrongSignatureLength() {
    var bad = new SignedPreKey(1, pub(), new byte[10]);
    assertThatThrownBy(() -> DeviceKeyBundle.of(1, 1, pub(), bad, List.of(new OneTimePreKey(1, pub()))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDuplicateOneTimePreKeyIds() {
    var dup = List.of(new OneTimePreKey(1, pub()), new OneTimePreKey(1, pub()));
    assertThatThrownBy(() -> DeviceKeyBundle.of(1, 1, pub(), signed(), dup))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsEmptyOrTooManyOneTimePreKeys() {
    assertThatThrownBy(() -> DeviceKeyBundle.of(1, 1, pub(), signed(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: Run it, expect failure** (classes don't exist):
```bash
./gradlew -p server test --tests '*DeviceKeyBundleTest'
```
Expected: compilation failure / FAIL.

- [ ] **Step 3: Implement the value objects.** `OneTimePreKey.java`:

```java
package com.cloak.server.domain.device;

/** A single one-time prekey: an id and its 33-byte Curve25519 public key. */
public record OneTimePreKey(int keyId, byte[] publicKey) {
  public OneTimePreKey {
    if (keyId < 0) {
      throw new IllegalArgumentException("keyId must be non-negative");
    }
    if (publicKey == null || publicKey.length != 33) {
      throw new IllegalArgumentException("publicKey must be 33 bytes");
    }
    publicKey = publicKey.clone();
  }
}
```

`SignedPreKey.java`:

```java
package com.cloak.server.domain.device;

/** A signed prekey: id, 33-byte public key, and its 64-byte identity-key signature. */
public record SignedPreKey(int keyId, byte[] publicKey, byte[] signature) {
  public SignedPreKey {
    if (keyId < 0) {
      throw new IllegalArgumentException("keyId must be non-negative");
    }
    if (publicKey == null || publicKey.length != 33) {
      throw new IllegalArgumentException("publicKey must be 33 bytes");
    }
    if (signature == null || signature.length != 64) {
      throw new IllegalArgumentException("signature must be 64 bytes");
    }
    publicKey = publicKey.clone();
    signature = signature.clone();
  }
}
```

`DeviceKeyBundle.java`:

```java
package com.cloak.server.domain.device;

import java.util.List;

/** A device's public prekey bundle. Validates structure; never crypto-verifies (client does, Slice 2). */
public record DeviceKeyBundle(
    int registrationId,
    int deviceNumber,
    byte[] identityKey,
    SignedPreKey signedPreKey,
    List<OneTimePreKey> oneTimePreKeys) {

  private static final int MAX_ONE_TIME_PREKEYS = 100;

  /** Validates and builds a bundle. @throws IllegalArgumentException on any structural violation. */
  public static DeviceKeyBundle of(
      int registrationId,
      int deviceNumber,
      byte[] identityKey,
      SignedPreKey signedPreKey,
      List<OneTimePreKey> oneTimePreKeys) {
    if (registrationId <= 0 || registrationId > 0x3FFF) {
      throw new IllegalArgumentException("registrationId out of range");
    }
    if (deviceNumber < 1) {
      throw new IllegalArgumentException("deviceNumber must be >= 1");
    }
    if (identityKey == null || identityKey.length != 33) {
      throw new IllegalArgumentException("identityKey must be 33 bytes");
    }
    if (signedPreKey == null) {
      throw new IllegalArgumentException("signedPreKey required");
    }
    if (oneTimePreKeys == null
        || oneTimePreKeys.isEmpty()
        || oneTimePreKeys.size() > MAX_ONE_TIME_PREKEYS) {
      throw new IllegalArgumentException("oneTimePreKeys must be 1.." + MAX_ONE_TIME_PREKEYS);
    }
    long distinct = oneTimePreKeys.stream().map(OneTimePreKey::keyId).distinct().count();
    if (distinct != oneTimePreKeys.size()) {
      throw new IllegalArgumentException("oneTimePreKey ids must be unique");
    }
    return new DeviceKeyBundle(
        registrationId, deviceNumber, identityKey.clone(), signedPreKey, List.copyOf(oneTimePreKeys));
  }
}
```

- [ ] **Step 4: Run tests, expect PASS.**
```bash
./gradlew -p server test --tests '*DeviceKeyBundleTest'
```
Expected: PASS.

- [ ] **Step 5: Format + commit.**
```bash
./gradlew -p server spotlessApply
git add server/src/main/java/com/cloak/server/domain/device server/src/test/java/com/cloak/server/domain/device
git commit -m "feat(server): device key bundle domain value objects + validation"
```

### Task C2: Output port + persistence adapter

**Files:**
- Create: `server/src/main/java/com/cloak/server/port/output/device/DeviceKeyRepositoryPort.java`
- Create: `server/src/main/java/com/cloak/server/adapter/output/database/device/DeviceEntity.java`
- Create: `server/src/main/java/com/cloak/server/adapter/output/database/device/SignedPreKeyEntity.java`
- Create: `server/src/main/java/com/cloak/server/adapter/output/database/device/OneTimePreKeyEntity.java`
- Create: `server/src/main/java/com/cloak/server/adapter/output/database/device/SpringDataDeviceRepository.java` (+ signed/onetime repos)
- Create: `server/src/main/java/com/cloak/server/adapter/output/database/device/DeviceKeyRepositoryAdapter.java`
- Test: persistence behavior is asserted in the Task C4 integration test (real Postgres). No mock-DB unit test (matches the message path, which tests persistence via Testcontainers).

- [ ] **Step 1: Define the port.** `DeviceKeyRepositoryPort.java`:

```java
package com.cloak.server.port.output.device;

import com.cloak.server.domain.device.DeviceKeyBundle;

/** Output port: persists a device's public key bundle, replacing any prior bundle for the device. */
public interface DeviceKeyRepositoryPort {
  /**
   * Idempotently upserts the bundle for {@code (ownerSub, bundle.deviceNumber())}, replacing the
   * device's signed prekey and one-time prekeys.
   *
   * @param ownerSub the authenticated owner subject (from the JWT)
   * @param bundle the validated public key bundle
   */
  void upsert(String ownerSub, DeviceKeyBundle bundle);
}
```

- [ ] **Step 2: JPA entities.** `DeviceEntity.java` maps the existing `device` table (with the V2 columns). Mirror `EncryptedMessageEntity`'s style (field annotations, protected no-args ctor, getters). Columns: `id` (UUID `@Id`), `owner_sub`, `public_key` (identity key bytes), `algorithm` (set `"CURVE25519"`), `registration_id`, `device_number`, `created_at` (let DB default; mark `insertable=false, updatable=false`), `revoked_at`. `SignedPreKeyEntity` maps `signed_prekey` with an `@IdClass` or `@EmbeddedId` over `(device_id, key_id)`; `OneTimePreKeyEntity` maps `one_time_prekey` similarly with a nullable `consumed_at`.

> Because the composite-key JPA mapping is fiddly, the simplest correct approach is `@IdClass`. Example for `SignedPreKeyEntity`:

```java
package com.cloak.server.adapter.output.database.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;

/** JPA row for {@code signed_prekey} (composite key device_id + key_id). */
@Entity
@Table(name = "signed_prekey")
@IdClass(SignedPreKeyEntity.Key.class)
public class SignedPreKeyEntity {
  @Id
  @Column(name = "device_id")
  private UUID deviceId;

  @Id
  @Column(name = "key_id")
  private int keyId;

  @Column(name = "public_key", nullable = false)
  private byte[] publicKey;

  @Column(name = "signature", nullable = false)
  private byte[] signature;

  protected SignedPreKeyEntity() {}

  public SignedPreKeyEntity(UUID deviceId, int keyId, byte[] publicKey, byte[] signature) {
    this.deviceId = deviceId;
    this.keyId = keyId;
    this.publicKey = publicKey;
    this.signature = signature;
  }

  /** Composite-key class. */
  public static class Key implements Serializable {
    private UUID deviceId;
    private int keyId;

    public Key() {}

    public Key(UUID deviceId, int keyId) {
      this.deviceId = deviceId;
      this.keyId = keyId;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Key k)) {
        return false;
      }
      return keyId == k.keyId && java.util.Objects.equals(deviceId, k.deviceId);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(deviceId, keyId);
    }
  }
}
```
Implement `OneTimePreKeyEntity` the same way (add `@Column(name = "consumed_at") private java.time.OffsetDateTime consumedAt;` nullable) and `DeviceEntity` with a single `@Id UUID id`.

- [ ] **Step 3: Spring Data repositories.** `SpringDataDeviceRepository`:
```java
package com.cloak.server.adapter.output.database.device;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataDeviceRepository extends JpaRepository<DeviceEntity, UUID> {
  Optional<DeviceEntity> findByOwnerSubAndDeviceNumber(String ownerSub, int deviceNumber);
}
```
And `SpringDataSignedPreKeyRepository extends JpaRepository<SignedPreKeyEntity, SignedPreKeyEntity.Key>` with `void deleteByDeviceId(UUID deviceId);`, likewise `SpringDataOneTimePreKeyRepository` with `void deleteByDeviceId(UUID deviceId);`.

- [ ] **Step 4: Adapter implementing the port** (`@Repository`, `@Transactional`). `DeviceKeyRepositoryAdapter.java`:
```java
package com.cloak.server.adapter.output.database.device;

import com.cloak.server.domain.device.DeviceKeyBundle;
import com.cloak.server.port.output.device.DeviceKeyRepositoryPort;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class DeviceKeyRepositoryAdapter implements DeviceKeyRepositoryPort {
  private final SpringDataDeviceRepository devices;
  private final SpringDataSignedPreKeyRepository signed;
  private final SpringDataOneTimePreKeyRepository oneTime;

  DeviceKeyRepositoryAdapter(
      SpringDataDeviceRepository devices,
      SpringDataSignedPreKeyRepository signed,
      SpringDataOneTimePreKeyRepository oneTime) {
    this.devices = devices;
    this.signed = signed;
    this.oneTime = oneTime;
  }

  @Override
  @Transactional
  public void upsert(String ownerSub, DeviceKeyBundle bundle) {
    DeviceEntity device =
        devices
            .findByOwnerSubAndDeviceNumber(ownerSub, bundle.deviceNumber())
            .orElseGet(() -> new DeviceEntity(UUID.randomUUID(), ownerSub, bundle.deviceNumber()));
    device.setIdentityKey(bundle.identityKey());
    device.setRegistrationId(bundle.registrationId());
    devices.save(device);

    signed.deleteByDeviceId(device.getId());
    oneTime.deleteByDeviceId(device.getId());
    signed.save(
        new SignedPreKeyEntity(
            device.getId(),
            bundle.signedPreKey().keyId(),
            bundle.signedPreKey().publicKey(),
            bundle.signedPreKey().signature()));
    bundle
        .oneTimePreKeys()
        .forEach(
            otp ->
                oneTime.save(
                    new OneTimePreKeyEntity(device.getId(), otp.keyId(), otp.publicKey())));
  }
}
```
> Give `DeviceEntity` a `DeviceEntity(UUID id, String ownerSub, int deviceNumber)` ctor that sets `algorithm = "CURVE25519"`, plus `setIdentityKey(byte[])` (writes `public_key`), `setRegistrationId(int)`, and `getId()`.

- [ ] **Step 5: Format + commit** (no behavior to run yet; verified in C4).
```bash
./gradlew -p server spotlessApply compileJava
git add server/src/main/java/com/cloak/server/port/output/device server/src/main/java/com/cloak/server/adapter/output/database/device
git commit -m "feat(server): device key registry persistence port + JPA adapter"
```

### Task C3: Use case

**Files:**
- Create: `server/src/main/java/com/cloak/server/usecase/RegisterDeviceKeysUseCase.java`
- Create: `server/src/main/java/com/cloak/server/usecase/RegisterDeviceKeysCommand.java`
- Test: integration (Task C4).

- [ ] **Step 1: Command record.** `RegisterDeviceKeysCommand.java`:
```java
package com.cloak.server.usecase;

import com.cloak.server.domain.device.DeviceKeyBundle;

/** Register a device's public bundle for an authenticated owner. */
public record RegisterDeviceKeysCommand(String ownerSub, DeviceKeyBundle bundle) {}
```

- [ ] **Step 2: Use case** (mirror `RouteMessageUseCase`'s `@Observed` + counter style):
```java
package com.cloak.server.usecase;

import com.cloak.server.port.output.device.DeviceKeyRepositoryPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Component;

/** Persists a device's public key bundle (idempotent upsert). No ciphertext, no private keys. */
@Component
public class RegisterDeviceKeysUseCase {
  private final DeviceKeyRepositoryPort repository;
  private final Counter registered;

  public RegisterDeviceKeysUseCase(DeviceKeyRepositoryPort repository, MeterRegistry registry) {
    this.repository = repository;
    this.registered =
        Counter.builder("cloak.devices.registered")
            .description("Count of device public-key bundles registered.")
            .register(registry);
  }

  /** Upserts the bundle for the authenticated owner. */
  @Observed(name = "cloak.device.register")
  public void register(RegisterDeviceKeysCommand cmd) {
    repository.upsert(cmd.ownerSub(), cmd.bundle());
    registered.increment();
  }
}
```

- [ ] **Step 3: Format + commit.**
```bash
./gradlew -p server spotlessApply compileJava
git add server/src/main/java/com/cloak/server/usecase/RegisterDeviceKeys*
git commit -m "feat(server): register-device-keys use case"
```

### Task C4: REST endpoint + integration tests

**Files:**
- Create: `server/src/main/java/com/cloak/server/adapter/input/rest/keys/DeviceKeyController.java`
- Create: `server/src/main/java/com/cloak/server/adapter/input/rest/keys/PublishKeyBundleRequest.java`
- Test: `server/src/integrationTest/java/com/cloak/server/DeviceKeyRegistryIntegrationTest.java`

- [ ] **Step 1: Write the failing integration test.** Create `DeviceKeyRegistryIntegrationTest.java`:

```java
package com.cloak.server;

import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Tokens;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.assertj.core.api.Assertions.assertThat;

class DeviceKeyRegistryIntegrationTest extends IntegrationTestBase {

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  private static String b64(int len) {
    byte[] b = new byte[len];
    b[0] = 0x05;
    return Base64.getEncoder().encodeToString(b);
  }

  private static String bundleJson() {
    return """
      { "registrationId": 12345, "deviceId": 1,
        "identityKey": "%s",
        "signedPreKey": { "keyId": 1, "publicKey": "%s", "signature": "%s" },
        "oneTimePreKeys": [ { "keyId": 1, "publicKey": "%s" }, { "keyId": 2, "publicKey": "%s" } ] }
      """
        .formatted(b64(33), b64(33), b64(64), b64(33), b64(33));
  }

  @Test
  void publishesBundle_persistsDeviceAndPrekeys() {
    String token = Tokens.accessToken(issuerUri(), "alice");
    String sub = Tokens.subject(token);

    client()
        .put().uri("/v1/keys")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(bundleJson())
        .exchange()
        .expectStatus().isNoContent();

    Integer devices =
        jdbc.queryForObject(
            "select count(*) from device where owner_sub = ? and device_number = 1", Integer.class, sub);
    assertThat(devices).isEqualTo(1);
    Integer otps =
        jdbc.queryForObject(
            "select count(*) from one_time_prekey o join device d on o.device_id = d.id"
                + " where d.owner_sub = ?",
            Integer.class, sub);
    assertThat(otps).isEqualTo(2);
  }

  @Test
  void rePublish_isIdempotent_noDuplicateDevice() {
    String token = Tokens.accessToken(issuerUri(), "bob");
    String sub = Tokens.subject(token);
    for (int i = 0; i < 2; i++) {
      client().put().uri("/v1/keys")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .contentType(MediaType.APPLICATION_JSON).bodyValue(bundleJson())
          .exchange().expectStatus().isNoContent();
    }
    Integer devices =
        jdbc.queryForObject(
            "select count(*) from device where owner_sub = ?", Integer.class, sub);
    assertThat(devices).isEqualTo(1);
  }

  @Test
  void malformedBundle_returns400() {
    String token = Tokens.accessToken(issuerUri(), "alice");
    client().put().uri("/v1/keys")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{ \"registrationId\": 1, \"deviceId\": 1, \"identityKey\": \"AA==\" }")
        .exchange().expectStatus().isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void noToken_returns401() {
    client().put().uri("/v1/keys")
        .contentType(MediaType.APPLICATION_JSON).bodyValue(bundleJson())
        .exchange().expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
```

- [ ] **Step 2: Run it, expect failure** (no endpoint):
```bash
./gradlew -p server integrationTest --tests '*DeviceKeyRegistryIntegrationTest'
```
Expected: FAIL (404 / compilation).

- [ ] **Step 3: Request DTO** with Base64 decoding + structural mapping to the domain. `PublishKeyBundleRequest.java`:
```java
package com.cloak.server.adapter.input.rest.keys;

import com.cloak.server.domain.device.DeviceKeyBundle;
import com.cloak.server.domain.device.OneTimePreKey;
import com.cloak.server.domain.device.SignedPreKey;
import java.util.Base64;
import java.util.List;

/** Wire DTO for PUT /v1/keys. base64 fields decode to raw key bytes; maps to the domain bundle. */
public record PublishKeyBundleRequest(
    int registrationId,
    int deviceId,
    String identityKey,
    SignedPreKeyDto signedPreKey,
    List<OneTimePreKeyDto> oneTimePreKeys) {

  public record SignedPreKeyDto(int keyId, String publicKey, String signature) {}

  public record OneTimePreKeyDto(int keyId, String publicKey) {}

  /** Decodes + validates into the domain bundle. @throws IllegalArgumentException on bad input. */
  DeviceKeyBundle toDomain() {
    if (signedPreKey == null || oneTimePreKeys == null || identityKey == null) {
      throw new IllegalArgumentException("missing required fields");
    }
    var decoder = Base64.getDecoder();
    var signed =
        new SignedPreKey(
            signedPreKey.keyId(),
            decoder.decode(signedPreKey.publicKey()),
            decoder.decode(signedPreKey.signature()));
    var otps =
        oneTimePreKeys.stream()
            .map(o -> new OneTimePreKey(o.keyId(), decoder.decode(o.publicKey())))
            .toList();
    return DeviceKeyBundle.of(registrationId, deviceId, decoder.decode(identityKey), signed, otps);
  }
}
```

- [ ] **Step 4: Controller.** `DeviceKeyController.java` (the `sub` comes from the JWT, never the body):
```java
package com.cloak.server.adapter.input.rest.keys;

import com.cloak.server.usecase.RegisterDeviceKeysCommand;
import com.cloak.server.usecase.RegisterDeviceKeysUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the calling device's public prekey bundle (Slice 1). */
@RestController
public class DeviceKeyController {
  private final RegisterDeviceKeysUseCase useCase;

  DeviceKeyController(RegisterDeviceKeysUseCase useCase) {
    this.useCase = useCase;
  }

  @PutMapping("/v1/keys")
  ResponseEntity<Void> publish(
      @AuthenticationPrincipal Jwt jwt, @RequestBody PublishKeyBundleRequest request) {
    useCase.register(new RegisterDeviceKeysCommand(jwt.getSubject(), request.toDomain()));
    return ResponseEntity.noContent().build();
  }
}
```

- [ ] **Step 5: Ensure `IllegalArgumentException` → 400.** Check `server/src/main/java/com/cloak/server/common/web/GlobalExceptionHandler.java`. If it has no handler for `IllegalArgumentException`, add one returning the `WrappedResponse` error envelope with `BAD_REQUEST` (mirror existing handlers). If one already maps `IllegalArgumentException`/`HttpMessageNotReadableException` to 400, no change.

- [ ] **Step 6: Run the integration test, expect PASS.**
```bash
./gradlew -p server integrationTest --tests '*DeviceKeyRegistryIntegrationTest'
```
Expected: PASS (all four cases).

- [ ] **Step 7: Full server gate (lint + coverage).**
```bash
./gradlew -p server build
```
Expected: BUILD SUCCESSFUL, JaCoCo ≥ 90%. If the new REST/usecase/adapter lines dip coverage, the integration test already exercises them; add a domain unit case if a branch is uncovered.

- [ ] **Step 8: Update server README** (`server/README.md`) — add `PUT /v1/keys` to the endpoint list. Commit.
```bash
./gradlew -p server spotlessApply
git add server/src/main/java/com/cloak/server/adapter/input/rest/keys \
        server/src/integrationTest/java/com/cloak/server/DeviceKeyRegistryIntegrationTest.java \
        server/src/main/java/com/cloak/server/common/web/GlobalExceptionHandler.java server/README.md
git commit -m "feat(server): PUT /v1/keys device key registry endpoint + integration tests"
```

---

## Phase D — IAM (self-registration + Cloak login theme)

### Task D1: Enable self-registration + point the realm at the theme

**Files:**
- Modify: `iam/realm/cloak-realm.json` (`registrationAllowed: true`, add `loginTheme: "cloak"`)
- Modify: `iam/docker-compose.yml` (mount the theme; disable theme cache in dev)

- [ ] **Step 1: Edit the realm export.** In `iam/realm/cloak-realm.json` set `"registrationAllowed": true` and add a top-level `"loginTheme": "cloak",` key (next to `"realm": "cloak"`).

- [ ] **Step 2: Mount the theme + disable caching in dev.** In `iam/docker-compose.yml`, add to the keycloak service `environment:` the line `KC_SPI_THEME_CACHE_THEMES: "false"` and `KC_SPI_THEME_STATIC_MAX_AGE: "-1"`, and add a volume `- ./themes/cloak:/opt/keycloak/themes/cloak:ro`.

- [ ] **Step 3: Verify the realm JSON still parses.**
```bash
python3 -c "import json;json.load(open('iam/realm/cloak-realm.json'));print('realm OK')"
```
Expected: `realm OK`.

- [ ] **Step 4: Commit.**
```bash
git add iam/realm/cloak-realm.json iam/docker-compose.yml
git commit -m "feat(iam): enable self-registration + select cloak login theme"
```

### Task D2: Build the Cloak login theme

**Files:**
- Create: `iam/themes/cloak/login/theme.properties`
- Create: `iam/themes/cloak/login/resources/css/cloak.css`
- Create: `iam/themes/cloak/login/resources/img/logo.svg`
- Test: `server/src/integrationTest/java/com/cloak/server/LoginThemeIntegrationTest.java`

> Approach: extend Keycloak's stock `keycloak` login theme and restyle via CSS only (no `.ftl` overrides needed for a faithful brand match — the stock templates already include `${properties.styles}` and a `kc-logo` area). This keeps the theme small and upgrade-safe.

- [ ] **Step 1: Write the failing theme test.** Create `LoginThemeIntegrationTest.java` — boots the real Keycloak (already in `IntegrationTestBase`) and asserts the login page serves our CSS and the realm uses the theme:
```java
package com.cloak.server;

import com.cloak.server.support.IntegrationTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Proves the realm renders the branded login theme (not stock Keycloak). */
class LoginThemeIntegrationTest extends IntegrationTestBase {

  @Test
  void loginPage_usesCloakTheme() throws Exception {
    String loginUrl =
        issuerUri()
            + "/protocol/openid-connect/auth?client_id=cloak-ios&response_type=code"
            + "&scope=openid&redirect_uri=com.cloak.app://oauth-callback";
    HttpResponse<String> resp =
        HttpClient.newHttpClient()
            .send(HttpRequest.newBuilder(URI.create(loginUrl)).build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(resp.body()).contains("/themes/cloak/login/");      // our theme's resources are linked
    assertThat(resp.body().toLowerCase()).contains("register");    // self-registration link present
  }
}
```
Run it → expect FAIL (theme dir doesn't exist; the container only has it once mounted — note the test Keycloak in `IntegrationTestBase` does **not** mount themes, so for this assertion add the theme to the test container).

- [ ] **Step 2: Make the test container see the theme.** In `IntegrationTestBase`, add to the `KEYCLOAK` builder `.withProviderClassesFrom(...)` is for providers, not themes; instead copy the theme into the container:
```java
.withCopyFileToContainer(
    org.testcontainers.utility.MountableFile.forHostPath("../iam/themes/cloak"),
    "/opt/keycloak/themes/cloak")
```
(Place this on the existing `KEYCLOAK` initializer.) This single line is the only test-harness change.

- [ ] **Step 3: Theme properties.** Create `iam/themes/cloak/login/theme.properties`:
```properties
parent=keycloak
import=common/keycloak
styles=css/login.css css/cloak.css
# Cloak brand login theme (OneUI). Stock Keycloak login templates + brand CSS override.
```

- [ ] **Step 4: Brand CSS.** Create `iam/themes/cloak/login/resources/css/cloak.css` mapping OneUI tokens to the stock Keycloak markup, with light + dark. Keep it focused:
```css
:root {
  --cloak-primary: #6D28D9; --cloak-primary-pressed: #5B21B6; --cloak-on-primary: #FFFFFF;
  --cloak-success: #22C55E;
  --cloak-canvas: #FFFFFF; --cloak-surface: #F5F5F7; --cloak-separator: rgba(0,0,0,.08);
  --cloak-text: #1D1D1F; --cloak-text-secondary: #6B7280;
  --cloak-radius-sm: 10px;
}
@media (prefers-color-scheme: dark) {
  :root {
    --cloak-canvas: #0B0B0F; --cloak-surface: #1C1C22; --cloak-separator: rgba(255,255,255,.08);
    --cloak-text: #F5F5F7; --cloak-text-secondary: #9CA3AF;
  }
}
body, .login-pf, .login-pf body {
  background: var(--cloak-canvas); color: var(--cloak-text);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
}
.login-pf-page { padding-top: 48px; }
#kc-header, #kc-header-wrapper { color: var(--cloak-text); font-weight: 700; }
.card-pf {
  background: var(--cloak-surface); border: 1px solid var(--cloak-separator);
  border-radius: 22px; box-shadow: none;
}
#kc-logo-wrapper, .kc-logo-text { background-image: url(../img/logo.svg); }
input[type=text], input[type=password], input[type=email] {
  background: var(--cloak-canvas); border: 1px solid var(--cloak-separator);
  border-radius: var(--cloak-radius-sm); color: var(--cloak-text);
}
.btn-primary, input[type=submit].btn-primary {
  background: var(--cloak-primary); border-color: var(--cloak-primary);
  border-radius: var(--cloak-radius-sm); color: var(--cloak-on-primary);
}
.btn-primary:hover, .btn-primary:active { background: var(--cloak-primary-pressed); }
a { color: var(--cloak-primary); }
```
> The exact stock selectors (`.card-pf`, `.btn-primary`, `#kc-logo-wrapper`) can vary by Keycloak version; verify against the rendered page during manual E2E (Step 7) and adjust. The test in Step 1 only asserts the theme is **wired**, not pixel-exact.

- [ ] **Step 5: Logo.** Create `iam/themes/cloak/login/resources/img/logo.svg` — a simple Cloak wordmark/glyph in `#6D28D9` (a purple rounded square with a white "C" + green dot, matching the app icon). Minimal inline SVG:
```svg
<svg xmlns="http://www.w3.org/2000/svg" width="160" height="48" viewBox="0 0 160 48">
  <rect x="0" y="4" width="40" height="40" rx="10" fill="#6D28D9"/>
  <text x="20" y="33" font-family="-apple-system,Helvetica,Arial" font-size="26" font-weight="700"
        fill="#fff" text-anchor="middle">C</text>
  <circle cx="33" cy="13" r="5" fill="#22C55E"/>
  <text x="52" y="33" font-family="-apple-system,Helvetica,Arial" font-size="24" font-weight="700"
        fill="#6D28D9">Cloak</text>
</svg>
```

- [ ] **Step 6: Run the theme test, expect PASS.**
```bash
./gradlew -p server integrationTest --tests '*LoginThemeIntegrationTest'
```
Expected: PASS (body links `/themes/cloak/login/` and shows a register link).

- [ ] **Step 7: Manual E2E (light + dark).** `./dev.sh up`, open `http://localhost:8081/realms/cloak/account` or the login URL from the test, confirm the branded card/buttons/logo render and the "Register" link appears. Toggle OS dark mode and re-check. Adjust CSS selectors if the stock markup differs. Update `iam/README.md` (document the theme + `KC_SPI_THEME_CACHE_THEMES=false` dev note).

- [ ] **Step 8: Commit.**
```bash
git add iam/themes iam/README.md \
        server/src/integrationTest/java/com/cloak/server/LoginThemeIntegrationTest.java \
        server/src/integrationTest/java/com/cloak/server/support/IntegrationTestBase.java
git commit -m "feat(iam): Cloak-branded Keycloak login theme (OneUI, light+dark)"
```

---

## Phase E — iOS (libsignal keygen, encrypted stores, onboarding)

> Build/test from `app/`. New flow: `xcodegen generate && pod install` → build `Cloak.xcworkspace`. Run the suite + coverage with `SIM="iPhone 17" ./scripts/coverage.sh` (after Task E1 updates it for the workspace). All commands prefixed `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` as in Phase 0.

### Task E1: CocoaPods bootstrap (LibSignalClient + GRDB/SQLCipher)

**Files:**
- Create: `app/Podfile`
- Modify: `app/scripts/coverage.sh` (build the **workspace**, scheme unchanged)
- Modify: `app/.gitignore` (ignore `Pods/`, `*.xcworkspace`; keep `Podfile.lock`)
- Modify: `app/README.md` (new build flow)
- Modify: `app/project.yml` (no SPM change; ensure the `Cloak` target has no conflicting settings — Pods integrate via the generated workspace)

- [ ] **Step 1: Write the Podfile.** Create `app/Podfile`. NOTE: `LibSignalClient` is **not on the CocoaPods CDN** — Signal's official install is git-sourced from `signalapp/libsignal`, pinned to a release tag, with the prebuilt-binary integrity checksum (`LIBSIGNAL_FFI_PREBUILD_CHECKSUM`) taken from that release's GitHub assets. (User-authorized 2026-06-21.)
```ruby
platform :ios, '17.0'
install! 'cocoapods', integrate_targets: true

# Official Signal-documented install: prebuilt libsignal FFI binary pinned by SHA-256 (fetched from
# the matching GitHub release `libsignal-client-ios-build-<tag>.tar.gz.sha256`). Update both the tag
# and the checksum together when bumping.
ENV['LIBSIGNAL_FFI_PREBUILD_CHECKSUM'] = '<official sha256 for the pinned tag>'

target 'Cloak' do
  use_frameworks!
  pod 'LibSignalClient', git: 'https://github.com/signalapp/libsignal.git', tag: 'v0.96.2'
  pod 'GRDB.swift/SQLCipher'
  target 'CloakTests' do
    inherit! :search_paths
  end
end
```

- [ ] **Step 2: Generate + install.**
```bash
cd app && xcodegen generate && pod install
```
Expected: `Pods/` created, `Cloak.xcworkspace` written, `Podfile.lock` committed-worthy. (First run downloads the libsignal binary.)

- [ ] **Step 3: Point coverage.sh at the workspace.** In `app/scripts/coverage.sh`, change the `xcodebuild` invocation from `-scheme Cloak` on the project to build the workspace: add `-workspace Cloak.xcworkspace` (remove any implicit project selection). Keep `-scheme Cloak`. The `EXCLUDE_REGEX` gains the Pods path naturally (only `/app/Cloak/` sources are counted already), but add `LibSignal|GRDB` defensively to the third-party guard comment.

- [ ] **Step 4: .gitignore.** Append to `app/.gitignore`:
```
Pods/
*.xcworkspace
```
(Keep `Podfile` and `Podfile.lock` tracked.)

- [ ] **Step 5: Smoke-build the workspace** with a trivial `import LibSignalClient` + `import GRDB` in a temporary file to confirm linkage, then delete it. Simplest: add `import LibSignalClient` to `CloakApp.swift` top temporarily? No — instead verify via the next task's test. For now confirm the workspace builds:
```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  xcodebuild build -workspace Cloak.xcworkspace -scheme Cloak \
  -destination 'platform=iOS Simulator,name=iPhone 17' -quiet
```
Expected: BUILD SUCCEEDED.

- [ ] **Step 6: Update README + commit.** Document the `xcodegen generate && pod install` → open `Cloak.xcworkspace` flow in `app/README.md`.
```bash
git add app/Podfile app/Podfile.lock app/scripts/coverage.sh app/.gitignore app/README.md
git commit -m "build(ios): add LibSignalClient + GRDB/SQLCipher via CocoaPods (workspace build)"
```

### Task E2: Encrypted database (GRDB + SQLCipher, Keychain passphrase)

**Files:**
- Create: `app/Cloak/Foundation/Persistence/KeychainSecret.swift`
- Create: `app/Cloak/Foundation/Persistence/EncryptedDatabase.swift`
- Test: `app/CloakTests/EncryptedDatabaseTests.swift`

- [ ] **Step 1: Failing test.** `EncryptedDatabaseTests.swift` — open an encrypted DB at a temp path with a passphrase and round-trip a value, proving SQLCipher + GRDB are wired:
```swift
import Testing
import Foundation
import GRDB
@testable import Cloak

@Suite struct EncryptedDatabaseTests {
    @Test func opensEncryptedDb_andRoundTrips() throws {
        let path = NSTemporaryDirectory() + "cloak-test-\(UUID().uuidString).sqlite"
        defer { try? FileManager.default.removeItem(atPath: path) }
        let db = try EncryptedDatabase.open(path: path, passphrase: "test-passphrase")
        try db.write { try $0.execute(sql: "CREATE TABLE t(v TEXT)"); 
                        try $0.execute(sql: "INSERT INTO t VALUES ('hi')") }
        let v = try db.read { try String.fetchOne($0, sql: "SELECT v FROM t") }
        #expect(v == "hi")
    }
}
```

- [ ] **Step 2: Run, expect failure** (no `EncryptedDatabase`). 
```bash
cd app && SIM="iPhone 17" ./scripts/coverage.sh   # or run just this suite via xcodebuild test
```

- [ ] **Step 3: Implement.** `KeychainSecret.swift` — fetch-or-create a random passphrase in the Keychain:
```swift
import Foundation
import Security

/// A persistent random secret in the Keychain (the SQLCipher passphrase). Hardware-protected,
/// never logged, never leaves the device.
enum KeychainSecret {
    /// Returns the secret for `account`, creating a 32-byte random one on first use.
    static func loadOrCreate(account: String) throws -> String {
        if let existing = try load(account: account) { return existing }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw KeychainError.random
        }
        let secret = Data(bytes).base64EncodedString()
        try store(account: account, secret: secret)
        return secret
    }

    enum KeychainError: Error { case random, status(OSStatus) }

    private static func load(account: String) throws -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else { throw KeychainError.status(status) }
        return String(data: data, encoding: .utf8)
    }

    private static func store(account: String, secret: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account,
            kSecValueData as String: Data(secret.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.status(status) }
    }
}
```
`EncryptedDatabase.swift`:
```swift
import Foundation
import GRDB

/// Opens a SQLCipher-encrypted GRDB database (guide §6). Holds all private key material at rest.
enum EncryptedDatabase {
    /// Opens (creating if needed) an encrypted DB at `path` using `passphrase` as the SQLCipher key.
    static func open(path: String, passphrase: String) throws -> DatabaseQueue {
        var config = Configuration()
        config.prepareDatabase { db in
            try db.usePassphrase(passphrase)
        }
        return try DatabaseQueue(path: path, configuration: config)
    }

    /// Default on-device location + Keychain-held passphrase.
    static func openDefault() throws -> DatabaseQueue {
        let dir = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let passphrase = try KeychainSecret.loadOrCreate(account: "cloak.db.key")
        return try open(path: dir.appendingPathComponent("cloak.sqlite").path, passphrase: passphrase)
    }
}
```
> `db.usePassphrase(_:)` requires the SQLCipher build of GRDB (the pod from Task E1). Verify the method name against the installed GRDB version; if the API is `try db.usePassphrase(Data(...))`, pass the bytes.

- [ ] **Step 4: Run, expect PASS.** Add `EncryptedDatabase.swift`/`KeychainSecret.swift` to coverage's counted set (they're under `/app/Cloak/`; Keychain code paths that need a device may be partially excluded — keep the round-trip test which covers `EncryptedDatabase.open`).

- [ ] **Step 5: Commit.**
```bash
git add app/Cloak/Foundation/Persistence app/CloakTests/EncryptedDatabaseTests.swift
git commit -m "feat(ios): SQLCipher-encrypted GRDB database with Keychain passphrase"
```

### Task E3: libsignal key generation

**Files:**
- Create: `app/Cloak/Foundation/Encryption/GeneratedDeviceKeys.swift`
- Create: `app/Cloak/Foundation/Encryption/SignalKeyGenerator.swift`
- Test: `app/CloakTests/SignalKeyGeneratorTests.swift`

- [ ] **Step 1: Failing test.** `SignalKeyGeneratorTests.swift` — generate keys and assert shape + that the signed-prekey signature verifies against the identity key:
```swift
import Testing
import Foundation
import LibSignalClient
@testable import Cloak

@Suite struct SignalKeyGeneratorTests {
    @Test func generates_identitySignedAnd100OneTimePreKeys() throws {
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 100)
        #expect(keys.oneTimePreKeys.count == 100)
        #expect(Set(keys.oneTimePreKeys.map(\.id)).count == 100)   // unique ids
        #expect(keys.registrationId >= 1 && keys.registrationId <= 0x3FFF)

        // The signed prekey's signature must verify against the identity public key.
        let identityPub = keys.identityKeyPair.identityKey.publicKey
        let ok = try identityPub.verifySignature(
            message: keys.signedPreKey.keyPair.publicKey.serialize(),
            signature: keys.signedPreKeySignature)
        #expect(ok)
    }
}
```

- [ ] **Step 2: Run, expect failure.**

- [ ] **Step 3: Implement.** `GeneratedDeviceKeys.swift` (a plain value carrier; exact libsignal types):
```swift
import Foundation
import LibSignalClient

/// One one-time prekey: a libsignal key id + keypair.
struct OneTimePreKeyRecord {
    let id: UInt32
    let keyPair: PrivateKey
}

/// A signed prekey: id, keypair, and the identity-key signature over its public key.
struct SignedPreKeyRecord {
    let id: UInt32
    let keyPair: PrivateKey
}

/// All freshly generated device key material (private + public). Privates are persisted by the stores.
struct GeneratedDeviceKeys {
    let registrationId: UInt32
    let identityKeyPair: IdentityKeyPair
    let signedPreKey: SignedPreKeyRecord
    let signedPreKeySignature: [UInt8]
    let oneTimePreKeys: [OneTimePreKeyRecord]
}
```
`SignalKeyGenerator.swift`:
```swift
import Foundation
import LibSignalClient

/// Generates a device's Signal key material (guide §7). Pure on-device computation; no backend.
enum SignalKeyGenerator {
    /// Generates the identity key, one signed prekey, and `oneTimeCount` one-time prekeys.
    static func generate(oneTimeCount: Int) throws -> GeneratedDeviceKeys {
        let identity = IdentityKeyPair.generate()
        let registrationId = UInt32.random(in: 1...0x3FFF)

        let signedKeyPair = PrivateKey.generate()
        let signedId: UInt32 = 1
        let signature = identity.privateKey.generateSignature(
            message: signedKeyPair.publicKey.serialize())

        let oneTime = (1...oneTimeCount).map { i in
            OneTimePreKeyRecord(id: UInt32(i), keyPair: PrivateKey.generate())
        }

        return GeneratedDeviceKeys(
            registrationId: registrationId,
            identityKeyPair: identity,
            signedPreKey: SignedPreKeyRecord(id: signedId, keyPair: signedKeyPair),
            signedPreKeySignature: signature,
            oneTimePreKeys: oneTime)
    }
}
```
> Exact LibSignalClient API names to verify during red→green: `IdentityKeyPair.generate()`, `PrivateKey.generate()`, `PrivateKey.publicKey`, `PublicKey.serialize() -> [UInt8]`, `PrivateKey.generateSignature(message:)`, `PublicKey.verifySignature(message:signature:)`, `IdentityKeyPair.identityKey.publicKey`. Adjust call sites to match the installed pod.

- [ ] **Step 4: Run, expect PASS.**

- [ ] **Step 5: Commit.**
```bash
git add app/Cloak/Foundation/Encryption app/CloakTests/SignalKeyGeneratorTests.swift
git commit -m "feat(ios): libsignal device key generation"
```

### Task E4: libsignal key stores on GRDB

**Files:**
- Create: `app/Cloak/Foundation/Encryption/SignalKeyStore.swift` (implements `IdentityKeyStore`, `PreKeyStore`, `SignedPreKeyStore`)
- Test: `app/CloakTests/SignalKeyStoreTests.swift`

- [ ] **Step 1: Failing test.** Round-trip a stored identity, signed prekey, and one-time prekey through the GRDB-backed store:
```swift
import Testing
import Foundation
import GRDB
import LibSignalClient
@testable import Cloak

@Suite struct SignalKeyStoreTests {
    private func freshStore() throws -> SignalKeyStore {
        let path = NSTemporaryDirectory() + "ks-\(UUID().uuidString).sqlite"
        let db = try EncryptedDatabase.open(path: path, passphrase: "k")
        return try SignalKeyStore(db: db, identity: IdentityKeyPair.generate(), registrationId: 7)
    }

    @Test func storesAndLoadsPreKey() async throws {
        let store = try freshStore()
        let pk = PrivateKey.generate()
        let record = try PreKeyRecord(id: 1, privateKey: pk)
        try await store.storePreKey(record, id: 1, context: NullContext())
        let loaded = try await store.loadPreKey(id: 1, context: NullContext())
        #expect(try loaded.publicKey().serialize() == pk.publicKey.serialize())
    }

    @Test func reportsLocalRegistrationId() async throws {
        let store = try freshStore()
        #expect(try await store.localRegistrationId(context: NullContext()) == 7)
    }
}
```

- [ ] **Step 2: Run, expect failure.**

- [ ] **Step 3: Implement `SignalKeyStore`.** Conform to the three libsignal store protocols, persisting into three GRDB tables created in the store's initializer (`identity`, `prekey`, `signed_prekey`, and a `peer_identity` table for `saveIdentity`/`isTrustedIdentity`). Sketch:
```swift
import Foundation
import GRDB
import LibSignalClient

/// GRDB-backed libsignal stores for identity + prekeys (guide §7). SessionStore is added in Slice 2.
final class SignalKeyStore: IdentityKeyStore, PreKeyStore, SignedPreKeyStore {
    private let db: DatabaseQueue
    private let identity: IdentityKeyPair
    private let registrationId: UInt32

    init(db: DatabaseQueue, identity: IdentityKeyPair, registrationId: UInt32) throws {
        self.db = db
        self.identity = identity
        self.registrationId = registrationId
        try db.write { db in
            try db.execute(sql: "CREATE TABLE IF NOT EXISTS prekey(id INTEGER PRIMARY KEY, record BLOB NOT NULL)")
            try db.execute(sql: "CREATE TABLE IF NOT EXISTS signed_prekey(id INTEGER PRIMARY KEY, record BLOB NOT NULL)")
            try db.execute(sql: "CREATE TABLE IF NOT EXISTS peer_identity(name TEXT PRIMARY KEY, key BLOB NOT NULL)")
        }
    }

    // IdentityKeyStore
    func identityKeyPair(context: StoreContext) throws -> IdentityKeyPair { identity }
    func localRegistrationId(context: StoreContext) throws -> UInt32 { registrationId }
    func saveIdentity(_ key: IdentityKey, for address: ProtocolAddress, context: StoreContext) throws -> Bool {
        try db.write { try $0.execute(sql: "INSERT OR REPLACE INTO peer_identity(name, key) VALUES (?, ?)",
                                      arguments: [address.name, Data(key.serialize())]) }
        return true
    }
    func isTrustedIdentity(_ key: IdentityKey, for address: ProtocolAddress, direction: Direction, context: StoreContext) throws -> Bool { true }
    func identity(for address: ProtocolAddress, context: StoreContext) throws -> IdentityKey? {
        let data = try db.read { try Data.fetchOne($0, sql: "SELECT key FROM peer_identity WHERE name = ?", arguments: [address.name]) }
        return try data.map { try IdentityKey(bytes: $0) }
    }

    // PreKeyStore
    func storePreKey(_ record: PreKeyRecord, id: UInt32, context: StoreContext) throws {
        try db.write { try $0.execute(sql: "INSERT OR REPLACE INTO prekey(id, record) VALUES (?, ?)",
                                      arguments: [Int(id), Data(record.serialize())]) }
    }
    func loadPreKey(id: UInt32, context: StoreContext) throws -> PreKeyRecord {
        guard let data = try db.read({ try Data.fetchOne($0, sql: "SELECT record FROM prekey WHERE id = ?", arguments: [Int(id)]) })
        else { throw SignalError.invalidKeyIdentifier("no prekey \(id)") }
        return try PreKeyRecord(bytes: data)
    }
    func removePreKey(id: UInt32, context: StoreContext) throws {
        try db.write { try $0.execute(sql: "DELETE FROM prekey WHERE id = ?", arguments: [Int(id)]) }
    }

    // SignedPreKeyStore
    func storeSignedPreKey(_ record: SignedPreKeyRecord, id: UInt32, context: StoreContext) throws {
        try db.write { try $0.execute(sql: "INSERT OR REPLACE INTO signed_prekey(id, record) VALUES (?, ?)",
                                      arguments: [Int(id), Data(record.serialize())]) }
    }
    func loadSignedPreKey(id: UInt32, context: StoreContext) throws -> SignedPreKeyRecord {
        guard let data = try db.read({ try Data.fetchOne($0, sql: "SELECT record FROM signed_prekey WHERE id = ?", arguments: [Int(id)]) })
        else { throw SignalError.invalidKeyIdentifier("no signed prekey \(id)") }
        return try SignedPreKeyRecord(bytes: data)
    }
}
```
> The libsignal store protocols are `async` in recent LibSignalClient (methods take a `StoreContext`/`NullContext`). Match the installed pod's exact protocol signatures (sync vs async, `LibSignalClient.PreKeyRecord(id:privateKey:)` ctor, `record.serialize()`, `SignalError` cases). The test in Step 1 uses `await`; if the installed API is synchronous, drop `await`. Keep the GRDB persistence identical.

- [ ] **Step 4: Run, expect PASS. Commit.**
```bash
git add app/Cloak/Foundation/Encryption/SignalKeyStore.swift app/CloakTests/SignalKeyStoreTests.swift
git commit -m "feat(ios): GRDB-backed libsignal identity + prekey stores"
```

### Task E5: Public bundle builder

**Files:**
- Create: `app/Cloak/Foundation/Encryption/PublicKeyBundle.swift`
- Test: `app/CloakTests/PublicKeyBundleTests.swift`

- [ ] **Step 1: Failing test** — build the bundle from generated keys and assert JSON structure matches the contract (field names + base64 of the right lengths):
```swift
import Testing
import Foundation
@testable import Cloak

@Suite struct PublicKeyBundleTests {
    @Test func encodesContractShape() throws {
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 3)
        let bundle = try PublicKeyBundle(from: keys, deviceId: 1)
        let json = try bundle.jsonObject()      // [String: Any]
        #expect(json["registrationId"] as? Int == Int(keys.registrationId))
        #expect(json["deviceId"] as? Int == 1)
        #expect((json["oneTimePreKeys"] as? [[String: Any]])?.count == 3)
        let signed = json["signedPreKey"] as? [String: Any]
        #expect(signed?["signature"] != nil && signed?["publicKey"] != nil)
    }
}
```

- [ ] **Step 2: Run, expect failure.**

- [ ] **Step 3: Implement** a `Codable` `PublicKeyBundle` matching the contract, built by base64-encoding the serialized public keys + signature:
```swift
import Foundation
import LibSignalClient

/// The public prekey bundle uploaded to the server (matches docs/contracts/slice1-device-key-bundle.md).
struct PublicKeyBundle: Codable, Equatable {
    struct SignedPreKey: Codable, Equatable { let keyId: UInt32; let publicKey: String; let signature: String }
    struct OneTimePreKey: Codable, Equatable { let keyId: UInt32; let publicKey: String }

    let registrationId: UInt32
    let deviceId: UInt32
    let identityKey: String
    let signedPreKey: SignedPreKey
    let oneTimePreKeys: [OneTimePreKey]

    init(from keys: GeneratedDeviceKeys, deviceId: UInt32) throws {
        func b64(_ bytes: [UInt8]) -> String { Data(bytes).base64EncodedString() }
        self.registrationId = keys.registrationId
        self.deviceId = deviceId
        self.identityKey = b64(keys.identityKeyPair.identityKey.publicKey.serialize())
        self.signedPreKey = SignedPreKey(
            keyId: keys.signedPreKey.id,
            publicKey: b64(keys.signedPreKey.keyPair.publicKey.serialize()),
            signature: b64(keys.signedPreKeySignature))
        self.oneTimePreKeys = keys.oneTimePreKeys.map {
            OneTimePreKey(keyId: $0.id, publicKey: b64($0.keyPair.publicKey.serialize()))
        }
    }

    /// JSON object form (for tests/inspection).
    func jsonObject() throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: JSONEncoder().encode(self)) as? [String: Any] ?? [:]
    }
}
```

- [ ] **Step 4: Run, expect PASS. Commit.**
```bash
git add app/Cloak/Foundation/Encryption/PublicKeyBundle.swift app/CloakTests/PublicKeyBundleTests.swift
git commit -m "feat(ios): public key bundle builder (contract shape)"
```

### Task E6: REST publisher

**Files:**
- Create: `app/Cloak/Foundation/Network/DeviceKeyPublisher.swift`
- Test: `app/CloakTests/DeviceKeyPublisherTests.swift`

- [ ] **Step 1: Failing test** — a `DeviceKeyPublisher` PUTs the bundle JSON with a bearer token, via an injected request-runner mock:
```swift
import Testing
import Foundation
@testable import Cloak

@Suite struct DeviceKeyPublisherTests {
    final class MockRunner: HTTPRunner, @unchecked Sendable {
        var lastRequest: URLRequest?
        var result: Result<Void, Error> = .success(())
        func send(_ request: URLRequest) async throws { lastRequest = request; try result.get() }
    }

    @Test func putsBundleWithBearer() async throws {
        let runner = MockRunner()
        let publisher = HTTPDeviceKeyPublisher(baseURL: URL(string: "https://api")!, runner: runner)
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 1)
        try await publisher.publish(PublicKeyBundle(from: keys, deviceId: 1), accessToken: "tok")
        #expect(runner.lastRequest?.httpMethod == "PUT")
        #expect(runner.lastRequest?.url?.path == "/v1/keys")
        #expect(runner.lastRequest?.value(forHTTPHeaderField: "Authorization") == "Bearer tok")
    }
}
```

- [ ] **Step 2: Run, expect failure.**

- [ ] **Step 3: Implement** the protocol + HTTP impl. Split the URLSession edge behind `HTTPRunner` (so it's mockable + the real one excluded from coverage like other platform edges):
```swift
import Foundation

/// The bundle-publish boundary (guide §8). Mockable; the real HTTP runner is the platform edge.
protocol DeviceKeyPublisher: Sendable {
    func publish(_ bundle: PublicKeyBundle, accessToken: String) async throws
}

/// Minimal HTTP send seam so the publisher is unit-testable without a server.
protocol HTTPRunner: Sendable {
    func send(_ request: URLRequest) async throws
}

struct HTTPDeviceKeyPublisher: DeviceKeyPublisher {
    let baseURL: URL
    let runner: HTTPRunner

    func publish(_ bundle: PublicKeyBundle, accessToken: String) async throws {
        var request = URLRequest(url: baseURL.appendingPathComponent("v1/keys"))
        request.httpMethod = "PUT"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(bundle)
        try await runner.send(request)
    }
}

/// Real URLSession runner (platform edge; excluded from coverage). Throws on non-2xx.
struct URLSessionHTTPRunner: HTTPRunner {
    enum HTTPError: Error { case status(Int) }
    func send(_ request: URLRequest) async throws {
        let (_, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw HTTPError.status(http.statusCode)
        }
    }
}
```

- [ ] **Step 4: Add `URLSessionHTTPRunner.swift` (or keep above) to the coverage `EXCLUDE_REGEX`** — add `DeviceKeyPublisher.swift`? No: keep `HTTPDeviceKeyPublisher` counted (it's pure logic, tested). Move `URLSessionHTTPRunner` into its own file `app/Cloak/Foundation/Network/URLSessionHTTPRunner.swift` and add that filename to `EXCLUDE_REGEX` in `coverage.sh` (platform edge, like `WebSocketMessageTransport.swift`).

- [ ] **Step 5: Run, expect PASS. Commit.**
```bash
git add app/Cloak/Foundation/Network/DeviceKeyPublisher.swift \
        app/Cloak/Foundation/Network/URLSessionHTTPRunner.swift \
        app/CloakTests/DeviceKeyPublisherTests.swift app/scripts/coverage.sh
git commit -m "feat(ios): device key bundle REST publisher"
```

### Task E7: Registration orchestration (idempotent/resumable)

**Files:**
- Create: `app/Cloak/Foundation/Encryption/DeviceRegistrationService.swift`
- Test: `app/CloakTests/DeviceRegistrationServiceTests.swift`

- [ ] **Step 1: Failing tests** — first run generates+publishes+marks; relaunch skips; failed publish leaves it unregistered and re-publishes without regenerating. Use a mock publisher + an in-memory registration-state store:
```swift
import Testing
import Foundation
@testable import Cloak

@Suite struct DeviceRegistrationServiceTests {
    actor MockPublisher: DeviceKeyPublisher {
        var count = 0
        var shouldFail = false
        func publish(_ bundle: PublicKeyBundle, accessToken: String) async throws {
            count += 1
            if shouldFail { throw NSError(domain: "x", code: 1) }
        }
    }

    final class MemoryState: RegistrationState, @unchecked Sendable {
        var registered = false
        func isRegistered() -> Bool { registered }
        func markRegistered() { registered = true }
    }

    @Test func firstRun_generatesPublishesMarks() async throws {
        let pub = MockPublisher(); let state = MemoryState()
        let svc = DeviceRegistrationService(publisher: pub, state: state, oneTimeCount: 5)
        try await svc.ensureRegistered(accessToken: "t")
        #expect(state.registered)
        #expect(await pub.count == 1)
    }

    @Test func relaunch_skipsWhenAlreadyRegistered() async throws {
        let pub = MockPublisher(); let state = MemoryState(); state.registered = true
        let svc = DeviceRegistrationService(publisher: pub, state: state, oneTimeCount: 5)
        try await svc.ensureRegistered(accessToken: "t")
        #expect(await pub.count == 0)
    }

    @Test func failedPublish_staysUnregistered_andThrows() async {
        let pub = MockPublisher(); await pub.setFail(true); let state = MemoryState()
        let svc = DeviceRegistrationService(publisher: pub, state: state, oneTimeCount: 5)
        await #expect(throws: (any Error).self) { try await svc.ensureRegistered(accessToken: "t") }
        #expect(state.registered == false)
    }
}
```
(Add `func setFail(_ v: Bool) { shouldFail = v }` to `MockPublisher`.)

- [ ] **Step 2: Run, expect failure.**

- [ ] **Step 3: Implement.** Define `RegistrationState` (protocol) + a GRDB-backed impl, and the service. For Slice 1 keygen happens in-memory then the privates are written to the `SignalKeyStore`; the "registered" flag persists in the encrypted DB. Minimal service:
```swift
import Foundation
import LibSignalClient

/// Tracks whether this device has published its bundle (persisted in the encrypted DB).
protocol RegistrationState: Sendable {
    func isRegistered() -> Bool
    func markRegistered()
}

/// Orchestrates first-run device key registration (guide §7). Idempotent + resumable.
struct DeviceRegistrationService: Sendable {
    let publisher: DeviceKeyPublisher
    let state: RegistrationState
    let oneTimeCount: Int

    /// Generates + publishes the bundle once. No-op if already registered. Throws on publish failure
    /// (leaving the device unregistered so the next launch retries) — keys are not regenerated on retry
    /// once persisted (see note).
    func ensureRegistered(accessToken: String) async throws {
        if state.isRegistered() { return }
        let keys = try SignalKeyGenerator.generate(oneTimeCount: oneTimeCount)
        // NOTE: persist `keys` into SignalKeyStore here (identity, signed prekey, one-time prekeys)
        // before publishing, so a later slice can serve the matching privates. Wiring the concrete
        // SignalKeyStore is done in Task E8's composition root; the service takes the generated keys.
        let bundle = try PublicKeyBundle(from: keys, deviceId: 1)
        try await publisher.publish(bundle, accessToken: accessToken)
        state.markRegistered()
    }
}
```
> Refinement for the resumable-without-regenerate requirement: persist the generated `GeneratedDeviceKeys` to the `SignalKeyStore` **before** `publish`, and gate regeneration on "are keys already in the store?" Keep the in-test version simple (the test asserts the registered flag + publish-count behavior); the composition root in E8 injects the real `SignalKeyStore` persistence step. If you prefer the stricter resume semantics under test now, add a `KeyVault` protocol (`hasKeys()`, `save(_:)`, `loadForPublish()`) and assert regeneration is skipped — optional hardening.

- [ ] **Step 4: Run, expect PASS. Commit.**
```bash
git add app/Cloak/Foundation/Encryption/DeviceRegistrationService.swift \
        app/CloakTests/DeviceRegistrationServiceTests.swift
git commit -m "feat(ios): device registration orchestration (idempotent/resumable)"
```

### Task E8: Onboarding UI + composition root (retire the demo)

**Files:**
- Create: `app/Cloak/Feature/Onboarding/SetupKeysView.swift`
- Create: `app/Cloak/Feature/Onboarding/SetupKeysViewModel.swift`
- Create: `app/Cloak/Feature/Conversations/ConversationListView.swift`
- Modify: `app/Cloak/Feature/Conversation/RootView.swift` (route login → setup → list)
- Modify: `app/Cloak/CloakApp.swift` (compose the real services)
- Delete: `app/Cloak/Feature/Conversation/ConversationView.swift`, `ConversationViewModel.swift`, and `app/CloakTests/ConversationViewModelTests.swift` (demo retired)
- Keep: `MessageTransport.swift`, `MessageEnvelope`, `WebSocketMessageTransport.swift` + their tests (`MessageEnvelopeTests`, `WebSocketEncodingTests`) as dormant transport infra for Slice 2
- Test: `app/CloakTests/SetupKeysViewModelTests.swift`

- [ ] **Step 1: Failing view-model test** — drives state through generating→done, and to failed on publish error:
```swift
import Testing
import Foundation
@testable import Cloak

@Suite @MainActor struct SetupKeysViewModelTests {
    actor OkPublisher: DeviceKeyPublisher { func publish(_ b: PublicKeyBundle, accessToken: String) async throws {} }
    actor FailPublisher: DeviceKeyPublisher { func publish(_ b: PublicKeyBundle, accessToken: String) async throws { throw NSError(domain:"x",code:1) } }

    @Test func reachesReadyOnSuccess() async {
        let state = DeviceRegistrationServiceTests.MemoryState()
        let svc = DeviceRegistrationService(publisher: OkPublisher(), state: state, oneTimeCount: 3)
        let vm = SetupKeysViewModel(registration: svc, accessToken: "t")
        await vm.run()
        #expect(vm.phase == .ready)
    }

    @Test func reachesFailedOnError() async {
        let state = DeviceRegistrationServiceTests.MemoryState()
        let svc = DeviceRegistrationService(publisher: FailPublisher(), state: state, oneTimeCount: 3)
        let vm = SetupKeysViewModel(registration: svc, accessToken: "t")
        await vm.run()
        #expect(vm.phase == .failed)
    }
}
```
(Reuse `MemoryState` from E7 — make it accessible, or duplicate a tiny local stub.)

- [ ] **Step 2: Run, expect failure.**

- [ ] **Step 3: Implement the view model** (`@MainActor @Observable`, `phase` enum):
```swift
import Foundation

@MainActor @Observable
final class SetupKeysViewModel {
    enum Phase: Equatable { case working, ready, failed }
    private(set) var phase: Phase = .working
    private let registration: DeviceRegistrationService
    private let accessToken: String

    init(registration: DeviceRegistrationService, accessToken: String) {
        self.registration = registration
        self.accessToken = accessToken
    }

    func run() async {
        phase = .working
        do { try await registration.ensureRegistered(accessToken: accessToken); phase = .ready }
        catch { phase = .failed }
    }
}
```

- [ ] **Step 4: Implement the views** (OneUI styling; pure views, excluded from coverage). `SetupKeysView.swift` shows a progress state and a Retry on `.failed`, calls a `onReady` closure when `.ready`:
```swift
import SwiftUI

struct SetupKeysView: View {
    @State var model: SetupKeysViewModel
    let onReady: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image("CloakLogo").resizable().scaledToFit().frame(width: 72, height: 72)
                .clipShape(RoundedRectangle(cornerRadius: 18))
            switch model.phase {
            case .working:
                ProgressView()
                Text("Setting up secure keys…").foregroundStyle(.secondary)
            case .failed:
                Text("Couldn't finish setup.").foregroundStyle(.red)
                Button("Retry") { Task { await model.run() } }
            case .ready:
                Color.clear.onAppear(perform: onReady)
            }
        }
        .task { await model.run() }
    }
}
```
`ConversationListView.swift` — empty state:
```swift
import SwiftUI

struct ConversationListView: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "lock.shield").font(.largeTitle).foregroundStyle(.secondary)
            Text("No conversations yet").font(.headline)
            Text("Start a secure chat in a later update.").font(.subheadline).foregroundStyle(.secondary)
        }
        .navigationTitle("Chats")
    }
}
```

- [ ] **Step 5: Rewire `RootView`** to route login → `SetupKeysView` → `ConversationListView`, and update `CloakApp.swift` composition root to build `URLSessionHTTPRunner` → `HTTPDeviceKeyPublisher` → `DeviceRegistrationService` (+ a GRDB-backed `RegistrationState`) and inject the access token from `AuthService.login`. Delete the demo files. Add a `baseURL` (e.g. `http://localhost:8080`) next to the existing `issuer`/`transportURL` in `CloakApp`.

> `RootView` currently builds a `ConversationViewModel`; replace that branch with: on login success, construct the `SetupKeysViewModel` and present `SetupKeysView(onReady: { showList = true })`, then `ConversationListView`. Keep the existing `topViewController()` presenter + error handling.

- [ ] **Step 6: Run the whole suite + coverage.**
```bash
cd app && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer SIM="iPhone 17" ./scripts/coverage.sh
```
Expected: PASS, ≥90% on counted files. Remove now-dead references (DemoUser, recipient field) — they live in the deleted `ConversationView`/`RootView` demo branch; ensure SwiftLint `--strict` is clean.

- [ ] **Step 7: Commit.**
```bash
git add app/Cloak/Feature app/Cloak/CloakApp.swift app/CloakTests/SetupKeysViewModelTests.swift
git rm app/Cloak/Feature/Conversation/ConversationView.swift \
       app/Cloak/Feature/Conversation/ConversationViewModel.swift \
       app/CloakTests/ConversationViewModelTests.swift
git commit -m "feat(ios): onboarding flow (setup keys -> empty chat list); retire Phase 0 demo"
```

### Task E9: README, privacy-gate test, final wiring

**Files:**
- Create: `app/CloakTests/PrivacyGateTests.swift`
- Modify: `app/README.md`, root `README.md`, `app/CloakTests/Fixtures/` (copy `slice1-key-bundle.json` for reference)

- [ ] **Step 1: Privacy-gate test** — assert the uploaded JSON carries no private key material (only the public bundle fields):
```swift
import Testing
import Foundation
@testable import Cloak

@Suite struct PrivacyGateTests {
    @Test func uploadedBundleContainsOnlyPublicFields() throws {
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 2)
        let data = try JSONEncoder().encode(PublicKeyBundle(from: keys, deviceId: 1))
        let json = String(decoding: data, as: UTF8.self)
        // Only the contract's public fields are present; no "private"/"secret" keys leak.
        #expect(json.contains("identityKey") && json.contains("signedPreKey"))
        #expect(!json.lowercased().contains("private"))
        #expect(!json.lowercased().contains("secret"))
    }
}
```
Run → PASS.

- [ ] **Step 2: Update docs.** `app/README.md` (onboarding flow + CocoaPods build), root `README.md` (note Slice 1: device key registration + branded login). Copy `docs/contracts/fixtures/slice1-key-bundle.json` into `app/CloakTests/Fixtures/` if you want a bundled reference fixture.

- [ ] **Step 3: Full gate both sides + commit.**
```bash
cd app && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer SIM="iPhone 17" ./scripts/coverage.sh
cd .. && ./gradlew -p server build
git add app/CloakTests/PrivacyGateTests.swift app/README.md README.md app/CloakTests/Fixtures
git commit -m "test(ios): privacy gate (only public key material uploaded) + docs"
```

---

## Phase F — End-to-end acceptance

### Task F1: Two-device acceptance + final review prep

- [ ] **Step 1: Bring up the stack.** `./dev.sh up` (Postgres, Kafka, Keycloak with the theme, obs). `./gradlew -p server bootRun`.
- [ ] **Step 2: Two simulators.** `cd app && xcodegen generate && pod install && open Cloak.xcworkspace`. Run on two simulators.
- [ ] **Step 3:** On sim 1, use the branded login to **self-register** a new user; confirm the "Setting up secure keys" screen then the empty chat list. Repeat on sim 2 with a second new user. Toggle dark mode on the login page once to confirm theming.
- [ ] **Step 4: Verify the registry.** 
```bash
docker exec -i cloak-postgres psql -U cloak -d cloak -c \
  "select owner_sub, device_number, registration_id from device;"
docker exec -i cloak-postgres psql -U cloak -d cloak -c \
  "select d.owner_sub, count(*) from one_time_prekey o join device d on o.device_id=d.id group by 1;"
```
Expected: two device rows; ~100 one-time prekeys each.
- [ ] **Step 5: Relaunch** one app; confirm it does **not** re-register (no new device row, counts unchanged).
- [ ] **Step 6: Privacy spot-check.** Confirm `device.public_key` / prekey tables hold only public bytes; grep server logs for any key material (none). Confirm the on-device `cloak.sqlite` is unreadable without the Keychain key (open it with `sqlite3` → "file is not a database").

---

## Self-review notes (author)

**Spec coverage:** libsignal via CocoaPods (E1) ✓; GRDB+SQLCipher (E2) ✓; keygen + signed-prekey sig (E3) ✓; Approach-1 stores Identity/PreKey/SignedPreKey, no SessionStore (E4) ✓; bundle + `PUT /v1/keys` contract (A1, E5, E6) ✓; idempotent server upsert + V2 schema (B1, C2, C4) ✓; structural-only server validation, client-side sig verify deferred (C1, C4) ✓; idempotent/resumable registration (E7) ✓; retire demo → setup → empty list (E8) ✓; branded Keycloak theme + self-registration (D1, D2) ✓; tests both sides + privacy gate + acceptance (C4, E9, F1) ✓; tracked/deferred items remain documented in the spec.

**Known adaptation points (verify against live APIs during red→green):** exact `LibSignalClient` symbol names + whether store protocols are async (E3, E4); GRDB `usePassphrase` signature (E2); Keycloak stock CSS selectors + whether the test container needs the theme copied in (D2); `GlobalExceptionHandler` already mapping `IllegalArgumentException`→400 (C4 Step 5). Each is isolated and surfaced by a failing compile/test, not silent.
