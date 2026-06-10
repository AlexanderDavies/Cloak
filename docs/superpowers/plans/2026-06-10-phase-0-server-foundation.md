# Server Foundation & Quality Gates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Cloak server's build/quality foundation and integration-test harness so every later slice inherits working quality gates, real-infra tests, and the schema/contract single-sources-of-truth.

**Architecture:** Spring Boot 4 / Java 25 service following the hexagonal + DDD layout in `server/docs/ARCHITECTURE_GUIDE.md`. This plan adds: Checkstyle (Google) + JaCoCo (≥90%) gates wired to `check`; a separate `integrationTest` source set; Flyway pointed at the repo-level `db/migrations` (single source of truth); a singleton-Testcontainers `IntegrationTestBase` (Postgres + Kafka + Keycloak); an ArchUnit boundary test; and the `docs/contracts/` seam. It produces a green end-to-end context test that boots the app against real containers and applies the baseline migration.

**Tech Stack:** Spring Boot 4.0.6, Java 25, Gradle, Flyway, PostgreSQL, Spring Kafka, Testcontainers (Postgres/Kafka/Keycloak), JUnit 5 + AssertJ, ArchUnit, Checkstyle, JaCoCo.

**Workflow:** Work on branch `feature/phase-0-server-foundation` off `main`. Follow root `CLAUDE.md` → Engineering workflow & quality gates (squash + rebase before PR, skill **and** manual review before merge). All commands run from `server/` unless stated. Docker must be running for any task that starts containers; if Docker is unavailable, the implementer runs those verification steps locally and reports output.

---

## File Structure

- `server/build.gradle` — add plugins (spotless/google-java-format, checkstyle, jacoco), dependencies, `integrationTest` source set/task, gate wiring.
- `server/config/checkstyle/google_checks.xml` — Google Checkstyle ruleset (fetched, pinned).
- `server/config/checkstyle/checkstyle-suppressions.xml` — narrow suppressions.
- `server/src/main/resources/application.yml` — replaces `application.properties`; main config (virtual threads, datasource, Flyway → `../db/migrations`, JPA validate).
- `server/src/main/java/com/cloak/server/ServerApplication.java` — unchanged (bootstrap).
- `server/src/integrationTest/java/com/cloak/server/support/IntegrationTestBase.java` — singleton Testcontainers harness.
- `server/src/integrationTest/java/com/cloak/server/ContextLoadsIntegrationTest.java` — green end-to-end context + migration test.
- `server/src/test/java/com/cloak/server/architecture/ArchitectureBoundaryTest.java` — ArchUnit rules.
- `server/src/test/java/com/cloak/server/ServerApplicationTests.java` — **deleted** (moves to integrationTest).
- `db/migrations/V1__baseline_message_and_device.sql` — baseline schema (device + encrypted_message).
- `docs/contracts/README.md` — contract-artifact seam (single source of truth) description.
- `docs/contracts/phase0-message-envelope.md` — the Phase 0 WebSocket envelope contract.
- `server/README.md` — updated run/test instructions.

---

### Task 1: google-java-format (Spotless) + Checkstyle (Google) gate

> **Formatting note for the whole plan:** Google's Checkstyle ruleset enforces Google Java Style (2-space indentation, etc.). The code blocks in this plan are illustrative and may show 4-space indentation; **Spotless `googleJavaFormat` is the source of truth for formatting**. Run `./gradlew spotlessApply` after creating/editing any Java file and before each commit so the committed code matches the gate.

**Files:**
- Modify: `server/build.gradle`
- Create: `server/config/checkstyle/google_checks.xml`
- Create: `server/config/checkstyle/checkstyle-suppressions.xml`

- [ ] **Step 1: Fetch the pinned Google ruleset**

Run (from `server/`):
```bash
mkdir -p config/checkstyle
curl -sSfL -o config/checkstyle/google_checks.xml \
  https://raw.githubusercontent.com/checkstyle/checkstyle/checkstyle-10.18.2/src/main/resources/google_checks.xml
```
Expected: file exists; `grep -c module config/checkstyle/google_checks.xml` returns a non-zero count.

- [ ] **Step 2: Add a suppressions file**

Create `server/config/checkstyle/checkstyle-suppressions.xml`:
```xml
<?xml version="1.0"?>
<!DOCTYPE suppressions PUBLIC
  "-//Checkstyle//DTD SuppressionFilter Configuration 1.2//EN"
  "https://checkstyle.org/dtds/suppressions_1_2.dtd">
<suppressions>
  <!-- Suppress Javadoc-summary requirements on the Spring bootstrap class. -->
  <suppress files="ServerApplication\.java" checks="SummaryJavadoc"/>
</suppressions>
```

- [ ] **Step 3: Wire Spotless + Checkstyle into `build.gradle`**

Add `id 'com.diffplug.spotless' version '6.25.0'` and `id 'checkstyle'` to the `plugins {}` block, and add after the `java {}` block:
```groovy
spotless {
    java {
        target 'src/**/*.java'
        googleJavaFormat('1.24.0')
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

checkstyle {
    toolVersion = '10.18.2'
    configFile = file('config/checkstyle/google_checks.xml')
    configProperties = [
        'org.checkstyle.google.suppressionfilter.config': file('config/checkstyle/checkstyle-suppressions.xml').path
    ]
    maxWarnings = 0          // warnings fail the build
    ignoreFailures = false   // violations fail the build
}
```
(The Spotless plugin auto-wires `spotlessCheck` into `check`; Checkstyle auto-wires `checkstyleMain`/`checkstyleTest` into `check`.)

- [ ] **Step 4: Format existing sources to Google style**

Run: `./gradlew spotlessApply`
Expected: `ServerApplication.java` (and any other Java) reformatted to 2-space Google style. **BUILD SUCCESSFUL**.

- [ ] **Step 5: Verify the Checkstyle gate FAILS on a violation, then PASSES clean**

```bash
printf '\nclass X{int  y;}\n' >> src/main/java/com/cloak/server/ServerApplication.java
./gradlew checkstyleMain    # expect FAIL: Checkstyle violations
git checkout -- src/main/java/com/cloak/server/ServerApplication.java
./gradlew spotlessApply checkstyleMain   # expect BUILD SUCCESSFUL
```

- [ ] **Step 6: Commit**

```bash
git add build.gradle config/checkstyle src/main/java
git commit -m "$(printf 'build: add google-java-format (Spotless) and Google Checkstyle gate\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 2: JaCoCo coverage gate ≥90% (fails the build), with exclusions

**Files:**
- Modify: `server/build.gradle`

- [ ] **Step 1: Apply the plugin and configure aggregated coverage + verification**

Add `id 'jacoco'` to `plugins {}`. Append to `server/build.gradle`:
```groovy
jacoco {
    toolVersion = '0.8.12'
}

// Coverage is measured on meaningful code only (generated/config/bootstrap excluded),
// per root CLAUDE.md > Engineering workflow & quality gates.
def coverageExclusions = [
    'com/cloak/server/ServerApplication.class',
    'com/cloak/server/**/config/**'
]

tasks.named('jacocoTestReport') {
    // Aggregate unit + integration execution data.
    executionData fileTree(layout.buildDirectory).include('jacoco/*.exec')
    dependsOn tasks.matching { it.name == 'test' || it.name == 'integrationTest' }
    reports {
        xml.required = true
        html.required = true
    }
    afterEvaluate {
        classDirectories.setFrom(classDirectories.files.collect {
            fileTree(it) { exclude coverageExclusions }
        })
    }
}

tasks.named('jacocoTestCoverageVerification') {
    executionData fileTree(layout.buildDirectory).include('jacoco/*.exec')
    dependsOn tasks.matching { it.name == 'test' || it.name == 'integrationTest' }
    afterEvaluate {
        classDirectories.setFrom(classDirectories.files.collect {
            fileTree(it) { exclude coverageExclusions }
        })
    }
    violationRules {
        rule {
            limit {
                counter = 'INSTRUCTION'
                minimum = 0.90
            }
        }
    }
}
```

> Note: the `integrationTest` task is created in Task 3. This block references it, so apply Task 3 before running the gate (Step 2 here is verified at the end of Task 6 once there is covered code).

- [ ] **Step 2: Commit**

```bash
git add build.gradle
git commit -m "$(printf 'build: add JaCoCo 90%% coverage gate with exclusions\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 3: `integrationTest` source set and task

**Files:**
- Modify: `server/build.gradle`

- [ ] **Step 1: Declare the source set, configurations, and task**

Append to `server/build.gradle`:
```groovy
sourceSets {
    integrationTest {
        java.srcDir 'src/integrationTest/java'
        resources.srcDir 'src/integrationTest/resources'
        compileClasspath += sourceSets.main.output
        runtimeClasspath += sourceSets.main.output
    }
}

configurations {
    integrationTestImplementation.extendsFrom testImplementation
    integrationTestRuntimeOnly.extendsFrom testRuntimeOnly
}

tasks.register('integrationTest', Test) {
    description = 'Runs @SpringBootTest integration tests on Testcontainers.'
    group = 'verification'
    testClassesDirs = sourceSets.integrationTest.output.classesDirs
    classpath = sourceSets.integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter tasks.named('test')
    jvmArgs '-Djdk.tracePinnedThreads=full'   // ARCHITECTURE_GUIDE §12.7
}

tasks.named('check') {
    dependsOn tasks.named('integrationTest')
    dependsOn tasks.named('jacocoTestCoverageVerification')
}
```

- [ ] **Step 2: Verify Gradle recognises the task**

Run: `./gradlew tasks --group verification`
Expected: `integrationTest` appears in the list. `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add build.gradle
git commit -m "$(printf 'build: add integrationTest source set and task wired into check\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 4: Runtime dependencies + `application.yml`

**Files:**
- Modify: `server/build.gradle`
- Create: `server/src/main/resources/application.yml`
- Delete: `server/src/main/resources/application.properties`

- [ ] **Step 1: Add dependencies**

Replace the `dependencies {}` block in `server/build.gradle` with:
```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.kafka:spring-kafka'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'
    runtimeOnly 'org.postgresql:postgresql'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'com.tngtech.archunit:archunit-junit5:1.3.0'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'

    integrationTestImplementation 'org.springframework.boot:spring-boot-testcontainers'
    integrationTestImplementation 'org.testcontainers:junit-jupiter'
    integrationTestImplementation 'org.testcontainers:postgresql'
    integrationTestImplementation 'org.testcontainers:kafka'
    integrationTestImplementation 'org.springframework.kafka:spring-kafka-test'
    integrationTestImplementation 'com.github.dasniko:testcontainers-keycloak:3.4.0'
    integrationTestImplementation 'org.awaitility:awaitility'
}
```
(Testcontainers, awaitility, flyway, postgresql, spring-kafka versions are managed by the Spring Boot BOM; only ArchUnit and the Keycloak container pin explicit versions.)

- [ ] **Step 2: Create `application.yml`, delete `application.properties`**

Create `server/src/main/resources/application.yml`:
```yaml
spring:
  application:
    name: server
  threads:
    virtual:
      enabled: true          # ARCHITECTURE_GUIDE §3.1
  datasource:
    url: jdbc:postgresql://localhost:5432/cloak
    username: cloak
    password: cloak
  flyway:
    enabled: true
    # Single source of truth for the schema (root db/ folder), per db/CLAUDE.md.
    locations: filesystem:../db/migrations
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate     # Flyway owns the schema; JPA only validates
```
Then:
```bash
git rm src/main/resources/application.properties
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add build.gradle src/main/resources
git commit -m "$(printf 'build: add web/jpa/kafka/flyway deps and application.yml\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 5: Baseline Flyway migration (single source in `db/migrations`)

**Files:**
- Create: `db/migrations/V1__baseline_message_and_device.sql`
- Delete: `db/migrations/README.md`'s placeholder note is kept; only add the migration.

- [ ] **Step 1: Write the baseline migration**

Create `db/migrations/V1__baseline_message_and_device.sql` (paths are repo-root-relative):
```sql
-- Cloak baseline schema. Ciphertext only; users referenced by Keycloak `sub`.
-- Cleartext columns are routing/delivery metadata only (root CLAUDE.md principle 6).

CREATE TABLE device (
    id          UUID PRIMARY KEY,
    owner_sub   TEXT        NOT NULL,
    public_key  BYTEA       NOT NULL,
    algorithm   TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ
);

CREATE INDEX idx_device_owner ON device (owner_sub);

CREATE TABLE encrypted_message (
    id              UUID        PRIMARY KEY,
    sender_sub      TEXT        NOT NULL,   -- needed to route receipts back
    recipient_sub   TEXT        NOT NULL,   -- needed to route/fan-out delivery
    device_id       UUID,                    -- target device (multi-device, later)
    ciphertext      BYTEA       NOT NULL,    -- opaque; server never decrypts
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_message_recipient ON encrypted_message (recipient_sub, created_at);
```

- [ ] **Step 2: Commit** (verification happens in Task 6, which runs the migration against the Postgres container)

```bash
cd .. && git add db/migrations/V1__baseline_message_and_device.sql && cd server
git commit -m "$(printf 'feat(db): baseline Flyway migration for device + encrypted_message\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 6: Testcontainers harness + green end-to-end context test

**Files:**
- Delete: `server/src/test/java/com/cloak/server/ServerApplicationTests.java`
- Create: `server/src/integrationTest/java/com/cloak/server/support/IntegrationTestBase.java`
- Create: `server/src/integrationTest/java/com/cloak/server/ContextLoadsIntegrationTest.java`

- [ ] **Step 1: Remove the default `@SpringBootTest` from the unit tier**

`src/test` is for pure unit tests (no Spring). The starter-generated context test moves to `integrationTest`:
```bash
git rm src/test/java/com/cloak/server/ServerApplicationTests.java
```

- [ ] **Step 2: Write the singleton Testcontainers base**

Create `server/src/integrationTest/java/com/cloak/server/support/IntegrationTestBase.java`:
```java
package com.cloak.server.support;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("cloak")
            .withUsername("cloak")
            .withPassword("cloak");

    static final ConfluentKafkaContainer KAFKA =
        new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    static final KeycloakContainer KEYCLOAK =
        new KeycloakContainer("quay.io/keycloak/keycloak:26.0");

    static {
        Startables.deepStart(POSTGRES, KAFKA, KEYCLOAK).join();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        // Flyway runs against the same datasource; migrations come from ../db/migrations.
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("spring.threads.virtual.enabled", () -> "true");
        // Keycloak issuer for the resource server (validation wired in Plan 2).
        r.add("cloak.auth.issuer-uri", () -> KEYCLOAK.getAuthServerUrl() + "/realms/cloak");
    }
}
```

- [ ] **Step 3: Write the failing end-to-end test**

Create `server/src/integrationTest/java/com/cloak/server/ContextLoadsIntegrationTest.java`:
```java
package com.cloak.server;

import com.cloak.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ContextLoadsIntegrationTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoads_andFlywayAppliedBaselineSchema() {
        // The Flyway baseline (V1) must have created the message + device tables
        // in the Postgres container during context startup.
        Integer deviceTables = jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_name = 'device'",
            Integer.class);
        Integer messageTables = jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_name = 'encrypted_message'",
            Integer.class);

        assertThat(deviceTables).isEqualTo(1);
        assertThat(messageTables).isEqualTo(1);
    }
}
```

- [ ] **Step 4: Run it to verify it passes** (Docker required)

Run: `./gradlew integrationTest`
Expected: **PASS** — containers start, Flyway applies `V1` from `../db/migrations`, the table-existence assertions pass. (First run pulls images; allow a few minutes.)

If Docker is unavailable in this environment, report that the verification could not be run locally and must be run on a Docker-capable machine — do not mark the step complete on assumption.

- [ ] **Step 5: Run the full gate**

Run: `./gradlew check`
Expected: `checkstyleMain`, `checkstyleTest`, `test`, `integrationTest`, and `jacocoTestCoverageVerification` all run; **BUILD SUCCESSFUL**. (Coverage now has the context test exercising bootstrap wiring; the bootstrap class itself is excluded.)

- [ ] **Step 6: Commit**

```bash
git add src/integrationTest src/test
git commit -m "$(printf 'test: Testcontainers harness + green context/migration integration test\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 7: ArchUnit boundary test scaffold

**Files:**
- Create: `server/src/test/java/com/cloak/server/architecture/ArchitectureBoundaryTest.java`

- [ ] **Step 1: Write the boundary rules** (ARCHITECTURE_GUIDE §2.3)

Create `server/src/test/java/com/cloak/server/architecture/ArchitectureBoundaryTest.java`:
```java
package com.cloak.server.architecture;

import com.cloak.server.ServerApplication;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packagesOf = ServerApplication.class)
class ArchitectureBoundaryTest {

    @ArchTest
    static final ArchRule domainIsIsolated =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "jakarta..",
                "com.fasterxml..",
                "org.hibernate..")
            .allowEmptyShould(true)
            .because("domain must be pure Java — no Spring, JPA, Jackson, or other infra");

    @ArchTest
    static final ArchRule adaptersDoNotLeakIntoUseCases =
        noClasses().that().resideInAPackage("..usecase..")
            .should().dependOnClassesThat()
            .resideInAPackage("..adapter..")
            .allowEmptyShould(true)
            .because("use cases depend on ports, never on adapters");
}
```
(`allowEmptyShould(true)` keeps the rules green while the `domain`/`usecase` packages are still empty in this foundation slice.)

- [ ] **Step 2: Run it**

Run: `./gradlew test`
Expected: **PASS** (`ArchitectureBoundaryTest` green; no packages violate yet).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/cloak/server/architecture
git commit -m "$(printf 'test: ArchUnit domain-boundary rules scaffold\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 8: `docs/contracts/` seam + Phase 0 envelope contract

**Files:**
- Create: `docs/contracts/README.md`
- Create: `docs/contracts/phase0-message-envelope.md`

- [ ] **Step 1: Describe the seam**

Create `docs/contracts/README.md`:
```markdown
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
```

- [ ] **Step 2: Define the Phase 0 envelope**

Create `docs/contracts/phase0-message-envelope.md`:
```markdown
# Phase 0 — Message envelope (WebSocket)

The walking-skeleton message frame. The payload is an **opaque ciphertext blob**
(base64); the server never decrypts it. Real Signal sessions arrive in later slices.

## Frame (client → server → recipient)

```json
{
  "messageId": "uuid",
  "toSub": "string (Keycloak sub of recipient)",
  "fromSub": "string (Keycloak sub of sender)",
  "deviceId": "uuid (sender device)",
  "ciphertext": "base64 string"
}
```

## Cleartext-field justification (principle 6)
- `messageId` — dedupe/ordering.
- `toSub` — route/fan-out to the recipient.
- `fromSub` — route receipts back to the sender (Slice 8).
- `deviceId` — multi-device targeting (later slices).
- `ciphertext` — opaque; the only content, encrypted on-device.

`fromSub` as a cleartext field is revisited when sealed-sender is considered
(future hardening); for the MVP it stays cleartext for receipt routing.
```

- [ ] **Step 3: Commit**

```bash
cd .. && git add docs/contracts && cd server
git commit -m "$(printf 'docs(contracts): add contract seam and Phase 0 message envelope\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

### Task 9: Update the server README

**Files:**
- Modify: `server/README.md`

- [ ] **Step 1: Document the gates and test tiers**

In `server/README.md`, under `## Test`, replace the body with:
```markdown
## Test

```bash
./gradlew test              # fast unit + ArchUnit boundary tests
./gradlew integrationTest   # @SpringBootTest on Testcontainers (Docker required)
./gradlew check             # everything + Checkstyle (Google) + JaCoCo ≥90% gate
```

Integration tests start real Postgres, Kafka, and Keycloak containers and apply
the Flyway migrations from `../db/migrations`. Quality gates (Checkstyle, JaCoCo
≥90%) fail the build on violation — see root `CLAUDE.md`.
```

- [ ] **Step 2: Re-read the README** to confirm it is accurate and consistent (ARCHITECTURE_GUIDE §0.3).

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "$(printf 'docs: document quality gates and test tiers in server README\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

---

## Definition of Done (this plan)

- `./gradlew check` is green and runs Checkstyle (Google), unit + ArchUnit tests, `integrationTest` on Testcontainers, and the JaCoCo ≥90% verification.
- The context integration test boots the app against Postgres + Kafka + Keycloak containers and confirms Flyway applied the baseline schema from `db/migrations`.
- `docs/contracts/` exists with the seam description and the Phase 0 envelope.
- `server/README.md` documents the gates and test tiers.
- Branch ready for PR per root `CLAUDE.md` (squash + rebase, then skill **and** manual review before merge).

## Spec coverage (against the roadmap's Phase 0 carry-over items)

- ✅ Quality gates established (Checkstyle + JaCoCo) so later slices inherit them.
- ✅ Server Flyway + Testcontainers consume `db/migrations` (single source of truth).
- ✅ `docs/contracts/` seam stood up.
- ⏭️ Keycloak realm users + JWKS validation, WebSocket, persistence adapter, and Kafka round-trip are **Plan 2 (Server skeleton)**.
- ⏭️ iOS scaffold + SwiftLint/coverage are **Plan 3 (iOS skeleton)**.
```
