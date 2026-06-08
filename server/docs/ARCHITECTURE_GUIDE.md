# CLAUDE.md — Java Spring Boot Service Bootstrap Guide

**Purpose:** This file is a Claude bootstrap document. Drop it into a new Java Spring Boot service repo as `CLAUDE.md` (or load it as context) when scaffolding a service that needs to follow **Hexagonal Architecture + Domain-Driven Design + Behaviour-Driven Development**, developed under **mandatory TDDs**. It encodes the technical patterns proven in a high-traffic production microservice and is intentionally **opinionated** — apply it to any domain.

**Stack assumptions:**
- **Spring Web MVC on virtual threads** — imperative, blocking code; the JVM scheduler unmounts the carrier during I/O so throughput stays high without callback chains.
- **Persistence**: works with any strategy — JDBC, JPA, Spring Data JDBC, Flyway, DynamoDB, etc. (examples use JDBC/JPA but principles apply universally)
- **Gradle build**
- **Lombok**: constructor + getter only — no `@Data` on domain types
- **TDD via Spring Integration tests on Testcontainers + WireMock** — Testcontainers is the primary feedback loop
- **Karate BDD on top** as living / acceptance documentation

**Before writing code:** When asked to build a feature, start at the principles, then the failing integration test, then the code, then update the README. Read this file end-to-end before scaffolding. The five operating principles (ask not assume, validate assumptions, README-in-every-change, DRY/KISS/SOLID, runnable locally) govern every other rule in this guide.

---

## 0. Operating principles for Claude — read first, apply always

These rules govern every task in a codebase following this guide. They are **behaviours**, not architectural — apply them in every cycle, every conversation, every refactor. If you violate them, the rest of the guide is wasted effort.

### 0.1 Always ask, never assume

If a requirement is ambiguous, an integration shape isn't documented, or the existing pattern doesn't unambiguously cover the case — stop and ask the source. Use `AskUserQuestion` (or your equivalent clarification tool). Do not guess.

Examples of things to ask, never assume:
- The exact request / response shape of a new endpoint
- Queue topic, subscription, or exchange naming conventions
- Whether a field is nullable / required / immutable
- Whether a state transition is reversible (and what reversal looks like)
- What error codes / HTTP status to return for a given failure mode
- Retention, TTL, soft-delete vs hard-delete expectations
- The authentication / authorization model for a given endpoint or piece of data
- Whether an existing similar pattern in the codebase is the canonical one or a one-off you shouldn't propagate

The cost of one clarifying question is minutes. The cost of building the wrong thing is days — plus the trust hit of having to redo work.

When you genuinely have multiple plausible interpretations, present them as choices via `AskUserQuestion` rather than picking one silently.

### 0.2 Think hard, validate every assumption

**Before writing code:**
- State your assumptions explicitly — in your reasoning, in a brief message to the user when significant.
- Confirm evidence: read the existing code, run a query, inspect the schema, look at a real sample payload, check the actual configuration value, run a small probe script.
- Do not write code on top of "I think X works this way." Read X.

**Before claiming a task is done:**
- Re-verify each assumption the implementation rests on. Run the test. Hit the endpoint. Inspect the database row. Check the log line. Trigger the failure path.
- If you skipped verification because "the change was small," go verify it now. Small changes break the most because they get the least scrutiny.

**Memory is a starting point, not a source of truth.** A memory entry that names a file, function, or behavior describes the world *when the memory was written*. The codebase has moved since. Before acting on a memory, verify it still matches current state.

**Smell list — if you catch yourself thinking any of these, stop and validate:**
- "I'll just assume the pattern is the same as the other adapter."
- "It probably handles nulls."
- "The default config is probably fine."
- "It should work like the documentation says."
- "I don't need to read that file, the name tells me what it does."
- "The test will catch it if I'm wrong." (The test only catches it if the test actually asserts on it — check.)

### 0.3 Every change updates the README

The `README.md` of the service is a **living artefact**, not a one-time bootstrap document. Every change that affects how the service is built, run, configured, tested, deployed, or integrated **updates the README in the same commit** / **PR as the code change**. No exceptions.

What counts as a change requiring a README update:
- New endpoint, new event, new scheduled job, new feature flag
- New downstream dependency, new environment variable, new Spring profile
- Changed local run command, changed build command, changed test command
- New Testcontainer or WireMock container added to the integration harness (§12)
- Changed minimum Java / Spring Boot version
- Removed module, package, or significant class that external callers / documentation references
- New CI gate, changed deployment step, new release procedure
- Architecture decision worth recording (deviation from this guide, with rationale)

The README must, at minimum, answer:
1. **What does this service do?** — one-paragraph purpose, owners, upstream callers, downstream dependencies
2. **How do I run it locally?** — single command, against Testcontainers (§0.5)
3. **How do I run the tests?** — unit, integration, BDD component, chaos — each a single command
4. **What config does it need?** — link to typed config records, list required env vars
5. **Where is it deployed?** — link to pipeline, environment, rollback procedure
6. **Where do I read the architecture?** — link to this CLAUDE.md and any ADRs
7. **Who do I call?** — on-call rotation, support channel

**Failures:** If a PR changes behaviour but **not** the README, the PR is incomplete. Apply this rule as strictly as the TDD rule (§12). When you edit code, the README is part of the change set — not a follow-up task.

Before declaring any task complete, re-read the README and confirm it still describes the system you just changed. If it doesn't, update it now (§0.2).

### 0.4 Code-craft principles — DRY, KISS, SOLID

Every line of generated code must satisfy these principles. When they appear to conflict, **state the trade-off explicitly** and pick the option that best preserves future change-ability — not the one that minimises today's line count.

- **DRY — Don't Repeat Yourself:** Single source of truth for every concept, configuration value, mapping, and decision.
  - One `@ConfigurationProperties` record per concern (§11.1), reused everywhere — never the same `@Value` in two classes.
  - One mapper per boundary (§5.4) — never duplicate field-by-field translation in two places.
  - One retry policy bean (§7.2) reused by every handler — never copy-paste the backoff configuration.
  - One `WireMockContainer` per downstream (§12.4) in the test harness — never duplicate the singleton Testcontainers (§12.5) in each test class.
  - One verifier per output channel in the test harness — never reimplement DB assertion in each test.
  - **Premature abstraction** — beware two code paths that look identical but have different reasons to change. Apply the **rule of three**: extract a shared abstraction only when the **third concrete duplicate** appears and all three share the same axis of change. Never abstract for two occurrences, even if they look identical.

- **KISS — Keep It Simple, Stupid:** Choose the simplest design that meets the requirement; defer complexity until it's earned.
  - Default to straight-line, blocking code — virtual threads make it efficient (§3).
  - Don't add interfaces until the second implementation exists. Ports exist because adapters exist — not before.
  - Don't add abstractions in a system for hypothetical future requirements. **YAGNI**
  - Avoid clever one-liners; favour readable multi-line code.
  - Default error / fatal exception handler (§9) — only catch when you have a specific, named recovery in mind.

- **SOLID — applied to this architecture:**
  - **S — Single Responsibility:** Each class has one reason to change. The aggregate enforces business invariants. The use case orchestrates. The adapter persists. The mapper translates. Confusing roles is the most common refactor trigger — if a class starts doing two things, split it before adding more.
  - **O — Open/Closed:** New behaviour is added by **adding new types**, not by editing existing ones. Add a new `DomainEventProcessor` for a new side effect; don't modify the existing processor. Add a new adapter for a new infra; don't modify the port.
  - **L — Liskov Substitution:** Every adapter fully honours the port contract — no surprise nulls, no surprise exceptions, no missing fields. If the port returns `Optional<T>`, the adapter returns empty for not-found, never null. Integration tests written against the port should pass against any compliant adapter.
  - **I — Interface Segregation:** Ports are per-concern and hand-rolled. `OrderRepositoryPort` exposes the write-side appropriately. Read-only projections for analytics / admin live on a separate `OrderQueryPort`. Callers depend only on what they use.
  - **D — Dependency Inversion:** Adapters depend on the domain (the domain depends on nothing); the application depends on ports (abstractions); adapters depend on ports too. Concrete infra is always behind an interface that an inner layer owns.

### 0.5 Every app must be runnable locally

Every service built under this guide must be **runnable end-to-end on a developer's machine, with a single documented command, against containerised dependencies** — not against shared cloud resources, not against staging, not against a colleague's port-forward.

**What "runnable locally" means:**
- A single command (`./gradlew bootRun`, `make run`, or equivalent) brings the service up against Testcontainers / WireMock containers for every external dependency (§12)
- All required configuration is defaulted, generated at startup, or read from a checked-in `.env.example` — never an undocumented env var that "the team just knows"
- The README's "runnable locally" section (§0.3) is verified to actually work one-to-one, on a clean checkout, before any PR merges

**Why this is non-negotiable:**
- It is the foundation of the TDD loop (§12) — if you can't run the app locally, you cannot run the tests locally
- It removes the worst class of debugging trap: bugs that only reproduce against shared infra and require a deploy to investigate
- It is the correct mechanism for onboarding new engineers without days of environment-setup pairing
- It makes the local dev loop, the CI pipeline, and the integration test suite share **the same definition of "the system"** — same Postgres image, same Pub/Sub emulator, same Spring profile

**Failures:** If a change makes the service no longer runnable locally — a new shared-cloud dependency, a credential that cannot be provided locally, a new external API with no local mock alongside the change — add a containerised stand-in or a local mock alongside the change, update the run instructions in the README, and verify on a clean clone before merging.

---

These five principles (§0.1-§0.5) are the **highest-priority instructions in this entire guide**. Every other section is conditional on you applying them. Apply them on every cycle, every commit, every PR.

---

## 0.6 Applying this guide to Cloak

This guide is intentionally domain-agnostic — its running examples use a generic `Order` / payments domain. **This service is the Cloak server.** Read the **root [`Cloak/CLAUDE.md`](../../CLAUDE.md)** first: it owns the product-level invariants this section translates into engineering rules. The `Order` examples below stay as generic illustrations; use the mapping here to read them in Cloak's terms.

### 0.6.1 Non-negotiable Cloak invariants (from root `CLAUDE.md`)

These sit **above** even §0.1-§0.5 when they conflict — they are the reason the product exists:

- **The server is untrusted with message content.** It routes, fans out, and persists **ciphertext only**. It never holds, derives, or logs plaintext. E2EE happens on the client; the server has no decryption key.
- **On-device AI only.** No feature on this server may send message content (plaintext *or* ciphertext) to a hosted LLM or any external AI provider. Inference is the iOS client's job.
- **Graceful degradation.** Message delivery must behave identically over the primary WebSocket transport and the long-poll fallback. Don't design a flow that only works on one.
- **Privacy by design.** When a decision touches user data, default to the most restrictive option (shortest retention, least logging, narrowest exposure).

### 0.6.2 Generic → Cloak mapping

Read every later `Order` example through this table:

| Guide's generic example | Cloak equivalent |
|-------------------------|------------------|
| `Order` aggregate | `Message` (encrypted envelope), `Conversation`, `Device`, `UserAccount`, `PublicKeyBundle` |
| `OrderId` value object | `MessageId`, `ConversationId`, `DeviceId` |
| `PlaceOrderUseCase` | `RouteMessageUseCase`, `RegisterDeviceUseCase`, `PublishPublicKeyUseCase` |
| `OrderPlacedDomainEvent` | `MessageRoutedDomainEvent`, `MessageDeliveredDomainEvent`, `DeviceRegisteredDomainEvent` |
| `OrderRepositoryPort` | `MessageRepositoryPort`, `DeviceRepositoryPort`, `PublicKeyRepositoryPort` |
| Payments WireMock downstream (§12.4) | Push-notification service, e.g. APNs (§12.4 stub becomes `POST /notify`) |
| Kafka `orders.events` topic | `messages.fanout` — reliable async delivery + fan-out to offline / multi-device recipients |
| Optimistic-locking on a mutable `Order` | Most natural on `Conversation` membership and `Device` registration; a delivered `Message` envelope is effectively immutable |

### 0.6.3 A Cloak-native aggregate — server holds ciphertext only

The aggregate carries the **encrypted payload as opaque bytes**. There is no `plaintext` field, no `decrypt()` method, no getter that could expose content. This is the §1.2 rich-domain pattern with the privacy invariant baked into the type:

```java
public class Message extends DomainAggregate {
    private final MessageId id;
    private final ConversationId conversationId;
    private final DeviceId senderDevice;
    private final Ciphertext ciphertext;     // opaque — server cannot read it
    private MessageStatus status;            // ROUTED → FANNED_OUT → DELIVERED
    private final Instant sentAt;

    public class Delivery {
        public void markFannedOut(DomainClock clock, String actor) {
            if (status != MessageStatus.ROUTED) {
                throw new IllegalStateException("Can only fan out a ROUTED message");
            }
            status = MessageStatus.FANNED_OUT;
            updatedBy = actor;
            flagDirty();
            publishDomainEvent(MessageFannedOutDomainEvent.builder()
                .messageId(id).conversationId(conversationId).build());   // IDs only — never ciphertext
        }
    }
}

// Ciphertext is a value object wrapping bytes the server treats as opaque.
public record Ciphertext(byte[] value) {
    public Ciphertext { Objects.requireNonNull(value); }
    @Override public String toString() { return "Ciphertext[" + value.length + " bytes]"; }  // never dump bytes
}
```

Note the `toString()` override: it prevents accidental content leakage into logs or error messages (§0.6.4).

### 0.6.4 Privacy guardrails mapped onto the existing sections

Apply these **on top of** the referenced sections — they are Cloak-specific constraints the generic guide does not state:

- **§5.4 Mappers:** Map ciphertext and metadata only. No mapper ever produces, accepts, or logs plaintext. A persistence-row mapper stores the opaque `Ciphertext`; an event-payload mapper carries IDs + status, never the body.
- **§8 Domain events:** Event payloads carry identifiers, status, and routing metadata — **never message content** (see `MessageFannedOutDomainEvent` above). The Kafka fan-out publisher forwards the ciphertext envelope unchanged; it does not inspect it.
- **§9 Error handling:** Error responses and logs include stable error codes + IDs, **never** request payload content. Do not echo a failed message body back to the caller.
- **§10 Observability:** Log `messageId`, `conversationId`, `deviceId`, `traceId`, status — **never the body or ciphertext bytes**. The `Ciphertext.toString()` override (§0.6.3) is a backstop, not a licence to log it.
- **§11 Config / §0.5 runnable-locally:** No profile may point AI or message-routing at a hosted external service. Downstreams (push notifications) run as Testcontainers/WireMock locally (§12.4); there is no "send content to a cloud LLM" code path to mock, because it must not exist.
- **§12 / §13 Tests:** Integration tests assert the server **stored and forwarded ciphertext unchanged** and that no plaintext appears in any log line, DB column, or outbound event. Add an assertion that the persisted column equals the input ciphertext byte-for-byte.

---

## 1. Architectural principles

### 1.1 Hexagonal (Ports & Adapters)
- The domain has **zero infrastructure dependencies** — no Spring, no Jackson, no JDBC, no `@Component`, no `CompletableFuture`/`Future` in public signatures. Allowed: plain Java, `SLF4J`. Enforced by ArchUnit (§2.3).
- **Dependency direction:** the domain depends on nothing; the application (use cases) depends on ports; adapters depend on ports and implement them.
- Driving (input) adapters: REST controllers, queue listeners, scheduled jobs.
- Driven (output) adapters: repository implementations, external HTTP clients, event publishers.
- **Ports are interfaces owned by the inner layers; adapters implement them in the outer layer.**

### 1.2 Domain-Driven Design — rich, not anemic
- Domain objects maintain **behaviour**, not just data. Methods enforce invariants; clients tell the object what to do.
- Never expose setters for state that has business rules attached. State transitions go through business methods.
- Aggregates are responsible for transitioning their own child entities. Outside callers never reach into child entities directly.
- Use **inner classes on the aggregate** to group cohesive business facilities (see §4.3) — this is a deliberate pattern, not an anti-pattern.

### 1.3 TDD — the inner development loop
- Every change to production code is preceded by a failing test that requires it (§12).
- The primary test surface is **integration tests** running against Testcontainers + WireMock containers, not mock-heavy unit tests.
- Domain logic is also TDD'd, with pure unit tests, inside each green step.

### 1.4 BDD — the acceptance / documentation layer
- BDD features sit **on top of the TDD loop** as living documentation (§13).
- Two tiers: **use-case features** (single API operation, many edge cases) and **user-journey features** (multi-step end-to-end flows).
- Gherkin scenarios double as AI context — making the system legible to future Claude sessions.

### 1.5 Domain events for side-effect decoupling
- Aggregates publish domain events when significant state changes occur (§4.1).
- Events are **persisted-then-processed**: side effects fire only after the aggregate is durably saved (§5.2).
- Processors are pluggable strategies — adding a new side effect never modifies the aggregate (§8).

---

## 2. Project layout

Use separate source sets to isolate each test tier; the domain boundary itself is enforced by ArchUnit (§2.3):

```
src/
├── main/                      # Production code — domain + application + adapters
│   └── java/<base-pkg>/
│       ├── domain/            # Pure domain — no Spring, no infra deps
│       ├── port/output/       # Output port interfaces (repository, event, service)
│       ├── adapter/
│       │   ├── input/         # rest/, messaging/, scheduled/
│       │   └── output/        # database/, externalapi/, pubsub/, kafka/
│       ├── usecase/           # Application services (orchestration)
│       ├── domain/event/      # Domain event orchestrator + processors (infra-aware)
│       └── common/
│           ├── config/        # Spring @Configuration
│           ├── exception/     # Global exception handlers, error codes
│           └── ...            # Tracing, logging, correlation
│
├── test/                      # Fast unit tests (JUnit 5 + AssertJ + Mockito)
│   │                          #  = pure domain, mappers, value objects + ArchUnit boundary checks
│
├── integrationTest/           # @SpringBootTest + Testcontainers + WireMockContainer
│   │                          #  = the TDD inner loop (§12)
│
└── componentTest/             # Karate BDD acceptance + user-journey tests (§13)
    └── java/.../features/
        ├── usecase/
        ├── userjourney/
        └── resources/
```

**Gradle:** `integrationTest` and `componentTest` are separate Gradle source sets so each can be run in isolation. Domain boundary is enforced at test time via ArchUnit (§2.3) — no extra source set or Gradle wiring required.

### 2.1 Package naming

```
<base-pkg>
├── domain                     # Pure business logic — no Spring, no infra
├── port.output                # Output port interfaces
├── adapter                    # Adapter implementations
│   ├── input                  # Driving adapters
│   └── output                 # Driven adapters
├── usecase                    # Application Services
├── domain.event               # Domain event orchestration (uses Spring)
└── common                     # Shared infra (config, exceptions, observability)
```

### 2.2 File suffix conventions — **enforce these**

| Type                    | Suffix           | Example                              |
|-------------------------|------------------|--------------------------------------|
| Output port             | `Port`           | `OrderRepositoryPort`                |
| Adapter                 | `Adapter`        | `PostgresOrderRepositoryAdapter`     |
| Use case                | `UseCase`        | `PlaceOrderUseCase`                  |
| REST controller         | `Controller`     | `PlaceOrderController`               |
| Domain event            | `DomainEvent`    | `OrderPlacedDomainEvent`             |
| Event processor (infra) | `EventProcessor` | `OrderPlacedDomainEventProcessor`    |
| Inbound event handler   | `EventHandler`   | `PaymentReceivedEventHandler`        |
| Command (record)        | `Command`        | `PlaceOrderCommand`                  |
| Mapper                  | `Mapper`         | `PlaceOrderMapper`                   |
| Factory                 | `Factory`        | `OrderFactory`                       |
| Domain service          | `DomainService`  | `ValidateOrderDomainService`         |
| Integration test        | `IntegrationTest`| `PlaceOrderIntegrationTest`          |

### 2.3 Domain boundary enforcement — ArchUnit

The domain package must have zero infrastructure dependencies. Enforce this with an ArchUnit rule in `src/test` — no extra Gradle source set required, and it catches annotation imports that a separate classpath would miss:

```java
@AnalyzeClasses(packagesOf = Application.class)
class ArchitectureBoundaryTest {

    @ArchTest
    static final ArchRule domainIsIsolated =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "jakarta..",
                "com.fasterxml..",
                "org.hibernate.."
            )
            .because("domain must be pure Java — no Spring, JPA, Jackson, or other infra");

    @ArchTest
    static final ArchRule adaptersDoNotLeakIntoUseCases =
        noClasses().that().resideInAPackage("..usecase..")
            .should().dependOnClassesThat()
            .resideInAPackage("..adapter..");
}
```

---

## 3. Concurrency model — Web MVC + virtual threads

This guide is locked to **Spring Web MVC on Java 21+ virtual threads**. Reactive / WebFlux is out of scope. Write straight-line, imperative code that blocks freely — the JVM scheduler unmounts the carrier thread during I/O so throughput stays high without callback chains.

### 3.1 Enabling virtual threads (one switch)

```yaml
# application.yml
spring:
  threads:
    virtual:
      enabled: true
```

This makes Tomcat handle each incoming request on a fresh virtual thread, and `@Async`, `@Scheduled`, `@KafkaListener`, and blocking Pub/Sub listeners all execute on virtual threads. Confirm it works: logging `Thread.currentThread()` in a controller should print `VirtualThread[...]`.

### 3.2 Implementation choices that fit

| Concern                | Choice                                                                                              |
|------------------------|-----------------------------------------------------------------------------------------------------|
| Return types           | Plain `T`, `List<T>`, `Optional<T>`, `Stream<T>` — never `Mono`/`Flux`/`CompletableFuture` in public signatures |
| Persistence            | JDBC, JPA, Spring Data JDBC, plain `JdbcClient`                                                     |
| HTTP client            | `RestClient` (Spring 6.1+), blocking, virtual-thread-friendly                                       |
| Kafka                  | `@KafkaListener` (blocking) — one virtual thread per message                                        |
| GCP Pub/Sub            | `PubSubSubscriber` (blocking) or `SubscriberStub` with synchronous `pull`                          |
| Concurrency primitives | `ReentrantLock`, `Semaphore`, `BlockingQueue`, `StructuredTaskScope`                               |
| Retries                | `Resilience4j` (`Retry`) or endpoint `Retry` (`@Retryable`)                                        |
| Transactions           | `@Transactional` (declarative) or `TransactionTemplate` (programmatic)                             |
| Async functions        | `ExecutorService`/`VirtualThreadPerTaskExecutor` & `Future.get()` or `StructuredTaskScope`         |
| Test HTTP client       | `TestRestTemplate` (full HTTP) or `MockMvc` (no socket)                                             |
| Observability context  | MDC via Micrometer Context Propagation = virtual threads carry MDC like platform threads            |

### 3.3 Pitfalls — refuse them

- **`synchronized`** blocks pin the virtual thread to its carrier. Under contention, this defeats the entire concurrency model. Use `ReentrantLock` for any lock held across a blocking call — it knows about virtual threads and unmounts cleanly.
- **Connection pools** become the dominant bottleneck. You can have a million virtual threads waiting; you cannot have a million DB connections. Size HikariCP carefully and apply backpressure (queue + timeout before the pool — don't let a slow downstream exhaust connections).
- **`ThreadLocal`** works but is **wasteful** under virtual threads — every VT has its own copy. Prefer method-parameter or context-object passing for per-request state. `ScopedValue` (incubator / preview in Java 21+) is the long-term replacement.
- **Don't introduce `CompletableFuture` chains** for "performance." The whole point of virtual threads is to write blocking code without paying for it. If you reach for `thenCompose()` you are reintroducing reactive in a virtual syntax.
- **Native code pins too** — any native library (legacy JDBC drivers, image processing) can pin the carrier thread. Enable pinning detection with `-Djdk.tracePinnedThreads=full`.
- **Don't** share `RestClient` / `WebClient` request specs across threads — they are not stateless. Build per-call.

### 3.4 The domain layer doesn't care
The domain layer (§4) is pure Java. Virtual threads are an infrastructure concern handled in `usecase/` and `adapter/`. The domain never knows.

---

## 4. Domain layer rules

> Examples below use a generic `Order` domain. For the Cloak reading (`Message` / `Conversation` / `Device`, ciphertext-only aggregates), see §0.6.

### 4.1 Aggregate root — the core pattern
Every aggregate **extends** a `DomainAggregate` **base class** that owns an internal list of unpublished domain events:

```java
// src/domain/.../DomainAggregate.java
public abstract class DomainAggregate extends DomainEntity {
    private final List<DomainEventWithHeaders> domainEvents = new ArrayList<>();

    public List<DomainEventWithHeaders> getDomainEvents() {
        return List.copyOf(domainEvents);
    }

    protected void publishDomainEvent(DomainEvent event, DomainEventHeaders... headers) {
        domainEvents.add(DomainEventWithHeaders.create(event, DomainEventHeaders.createHeaders()));
    }

    public void clearDomainEvents() {
        domainEvents.clear();
    }
}
```

- `DomainEvent` is a **marker interface** (no methods) in the domain package.
- `DomainEntity` carries `version` (optimistic locking) and a `dirty` / `new` flag set by `flagDirty()` / `flagNew()` so the persistence layer knows whether to insert or update.

### 4.2 Aggregate construction — always via a factory

```java
public class OrderFactory {
    public Order create(CreateOrderDomainCommand cmd) {
        var order = Order.builder()
            .id(OrderId.create(cmd.customerId()))
            .status(OrderStatus.PENDING)
            .placedAt(cmd.placedAt())
            .createdBy(cmd.actor())
            .build();
        order.flagNew();
        return order;
    }
}

@Builder
public record CreateOrderDomainCommand(
    @NonNull String customerId,
    @NonNull String actor,
    @NonNull Instant placedAt) {}
```

- Factories enforce invariants during construction.
- Use **`record` + `@Builder`** for input commands (immutable, named-arg construction).
- Use `@NonNull` to make missing required fields a construction-time failure.

### 4.3 Inner classes for cohesive behavior groups
When an aggregate has multiple distinct behavioral concerns (e.g., `Synchronisation`, `Redaction`, `Completion`), expose them as **public inner classes** mounted on the aggregate. This keeps:
- The aggregate root readable (no 800-line god class)
- Behavior grouped by intent, not scattered across utility classes
- Private state accessible from the inner classes without exposing setters

```java
public class Order extends DomainAggregate {
    public final Cancellation cancellation = new Cancellation();
    public final Fulfilment fulfilment = new Fulfilment();

    public class Cancellation {
        public void apply(DomainClock clock, String reason, String actor) {
            if (status == OrderStatus.FULFILLED) {
                throw new IllegalStateException("Cannot cancel a fulfilled order");
            }
            status = OrderStatus.CANCELLED;
            cancelledAt = clock.now();
            updatedBy = actor;
            flagDirty();
            publishDomainEvent(OrderCancelledDomainEvent.builder()
                .orderId(id).reason(reason).build());
        }
    }
}
```

Callers write `order.cancellation.apply(clock, reason, actor)` — reads naturally and the intent is unambiguous.

### 4.4 Value objects
- **Immutable** — Java `record` is the default.
- Compared by value, no identity.
- Encapsulate validation in the canonical constructor.
- Examples: `OrderId`, `Money`, `EmailAddress`, `SingleTextValue`.

```java
public record OrderId(String customerId, UUID orderId) {
    public OrderId {
        Objects.requireNonNull(customerId);
        Objects.requireNonNull(orderId);
    }

    public static OrderId create(String customerId) {
        return new OrderId(customerId, UUID.randomUUID());
    }
}
```

### 4.5 Domain services
Use a **stateless** domain service when logic operates across multiple aggregates or doesn't naturally belong to any single one:

```java
public class ValidateOrderDomainService {
    public Optional<DomainViolation> validate(Order order, CustomerProfile profile) { ... }
}
```

Keep these in the domain layer, no Spring annotations.

### 4.6 `DomainClock` — never call `Instant.now()` directly
The domain takes time as a dependency:

```java
public interface DomainClock {
    Instant now();
}
```

A Spring bean implements it in production; tests inject a `MutableDomainClock` so expiry / TTL scenarios are deterministic. **This is the single highest-leverage testability pattern in the codebase.**

### 4.7 Collections of entities
Wrap child entity collections in a typed collection class (`InteractionsCollection`, `MessagesCollection`) that exposes domain-meaningful operations (`find`, `create`, `complete()`) rather than raw `List<T>`. This keeps the aggregate root free of collection-management noise and lets the collection enforce its own invariants.

---

## 5. Application layer (use cases)

### 5.1 The use-case orchestration pattern
A use case **loads → mutates → commits**. Side effects happen via the domain-event orchestrator (§8.1) after persistence, through the commit helper (§5.2). Plain return types — no `Mono` / `Future`.

```java
@Component
@RequiredArgsConstructor
public class PlaceOrderUseCase {
    private final DomainClock clock;
    private final OrderRepositoryPort orderRepository;
    private final OrderFactory orderFactory;
    private final CommitOrderAggregate commit;

    public OrderId place(PlaceOrderCommand cmd) {
        var order = orderFactory.create(toDomainCommand(cmd, clock));
        commit.commit(order);
        return order.id();
    }
}
```

### 5.2 The commit aggregate — non-negotiable shape
Every aggregate write goes through a tiny helper that **persists in a transaction, then publishes events after commit**:

```java
@Component
@RequiredArgsConstructor
public class CommitOrderAggregate {
    private final DomainEventOrchestrator orchestrator;
    private final OrderRepositoryPort repository;
    private final TransactionTemplate writeTransaction;    // configured @Bean

    public void commit(Order order) {
        writeTransaction.executeWithoutResult(status -> repository.save(order));
        orchestrator.process(order);
    }
}
```

**Why this matters:** events publish only if the transaction commits. No "we sent the email but the row never saved" bugs. The orchestrator clears events after dispatch so re-commits don't double-fire. **Do note:** put `orchestrator.process(...)` outside the transaction — keep side-effect publishing out of the DB transaction so a slow downstream can't hold the row lock.

### 5.3 Inbound event handlers
Queue listeners (Kafka, Pub/Sub) implement a shared interface so the inbound infra layer can dispatch them generically:

```java
public interface EventHandler<T> {
    void handle(T event, DomainEventHeaders headers);
    SubscribeType type();
}
```

Handlers typically: `find aggregate → mutate it → commit → retry on optimistic-lock failure (§7.2)`.

```java
@Component
@RequiredArgsConstructor
public class PaymentReceivedEventHandler implements EventHandler<PaymentReceivedEvent> {
    private final OrderRepositoryPort orders;
    private final CommitOrderAggregate commit;
    private final Retry optimisticLockRetry;                // Resilience4j (§7.2)

    @Override
    public void handle(PaymentReceivedEvent event, DomainEventHeaders headers) {
        Retry.decorateRunnable(optimisticLockRetry, () -> {
            var order = orders.find(event.orderId()).orElseThrow(OrderNotFound::new);
            order.fulfilment.markPaid(event.paymentId(), event.actor());
            commit.commit(order);
        }).run();
        log.info("Finished processing PaymentReceived [orderId:{}]", event.orderId());
    }

    @Override public SubscribeType type() { return SubscribeType.PAYMENT_RECEIVED; }
}
```

### 5.4 Mappers at every boundary (anti-corruption layer)
Each boundary has its own mapper. **External models never leak into the domain.**
- REST request DTO → use-case command (in `adapter/input/rest/<feature>/<Feature>Mapper`)
- Domain aggregate → persistence row (in `adapter/output/database/<feature>/<Feature>Mapper`)
- Domain event → outbound event payload (in `adapter/output/kafka/...`)

If a downstream model changes shape, **only the mapper changes** — the domain and use case stay stable.

---

## 6. Ports & adapters

### 6.1 Output port shape
- Lives in `port/output/<group>/`
- Method names are domain-meaningful (`get`, `find`, `save`, `findExpired`) — not CRUD-leaking (`selectById`).
- Returns domain types only.
- Returns: `T`, `Optional<T>`, `List<T>`, `Stream<T>`. Never `Mono` / `Flux` / `CompletableFuture` in port signatures.

```java
public interface OrderRepositoryPort {
    Order getOrder(OrderId id);                              // throws OrderNotFound
    Optional<Order> find(OrderId id);                        // empty for not-found
    void save(Order order);                                  // upserts based on aggregate's new/dirty flag
    List<ExpiredOrderProjection> findExpired(long limit);    // bounded result set
}
```

Use `Stream<...>` only for genuinely large result sets where the caller wants lazy iteration; close it with try-with-resources.

### 6.2 Adapter responsibilities
- Translate between domain and infrastructure (records → aggregate, SQL → command).
- Map persistence-level concurrency exceptions to a domain-aware `OptimisticLockingFailure` so retry logic can pattern-match (§7.2).
- Never expose framework types (`SQLException`, `DataAccessException`, `HibernateException`) outside the adapter.
- Adapters are `@Component` / `@Repository`; port lives in `port/output/...`; the adapter lives in `adapter/output/...`

### 6.3 Driving adapters — controller pattern
Controllers are **middleware: header extraction, validation, mapping, delegate**. **Zero business logic.** Correlation (trace ID, MDC) is handled by a servlet filter (§10.1), **not** in the controller.

```java
@RestController
@RequiredArgsConstructor
public class PlaceOrderController {
    private final PlaceOrderUseCase useCase;
    private final PlaceOrderMapper mapper;

    @PostMapping("/v1/orders")
    ResponseEntity<WrappedResponse<PlaceOrderResponse>> placeOrder(
        @RequestHeader("x-actor") String actor,
        @RequestBody PlaceOrderRequest request) {
        var orderId = useCase.place(mapper.toCommand(actor, request));
        return ResponseEntity.ok(WrappedResponse.of(mapper.toResponse(orderId)));
    }
}
```

---

## 7. Concurrency, retries, optimistic locking

### 7.1 Aggregate versioning
Every aggregate has a `version` field. The adapter:
1. Reads the row + version
2. On save, issues `UPDATE ... WHERE id = ? AND version = ?` and increments version (or, with JPA, relies on `@Version`)
3. If 0 rows affected → throw `OptimisticLockingFailure(aggregate)`

This domain-aware exception is what the retry layer pattern-matches on — not the framework's `OptimisticLockingFailureException`.

### 7.2 Retry policy — declare it once, reuse everywhere
Use **Resilience4j** as the canonical retry mechanism. Spring Retry's `@Retryable` is also acceptable for declarative use; pick one and apply uniformly.

```java
@Configuration
@RequiredArgsConstructor
public class RetryConfig {

    @Bean
    public Retry optimisticLockRetry(JdbcRetryProperties props) {
        var config = RetryConfig.custom()
            .maxAttempts(props.backoff().maxAttempts())
            .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                props.backoff().minBackoffMs(),
                2.0,
                props.backoff().jitter()))
            .retryOnException(this::isRetryable)
            .build();
        var retry = Retry.of("optimistic-lock", config);
        retry.getEventPublisher().onRetry(e ->
            log.warn("Retrying optimistic-lock failure [attempt:{}]", e.getNumberOfRetryAttempts()));
        return retry;
    }

    private boolean isRetryable(Throwable t) {
        if (t instanceof OptimisticLockingFailure) return true;
        if (t instanceof DataAccessException da && da.getCause() instanceof OptimisticLockingFailure) return true;
        log.error("Non-retryable exception detected — failing fast", t);
        return false;
    }
}
```

Usage at the call site:
```java
Retry.decorateRunnable(optimisticLockRetry, () -> commit.commit(order)).run();
```

Or, with Spring Retry:
```java
@Retryable(retryFor = OptimisticLockingFailure.class,
    maxAttemptsExpression = "#{retry.transaction.max-attempts}",
    backoff = @Backoff(delayExpression = "...", multiplierExpression = "..."))
public void commitOrder(Order order) { ... }
```

### 7.3 Transaction boundary
- **Write:** `@Transactional` on the persistence helper (`CommitOrderAggregate.commit`) or `TransactionTemplate` for programmatic control. The transaction wraps the DB write only — event publishing happens after commit.
- **Read:** `@Transactional(readOnly = true)` on the Spring context. `@Transactional(isolation = Isolation.SERIALIZABLE)` where you need consistency guarantees; otherwise omit the annotation and let auto-commit apply.
- The domain never knows about transactions. Annotations live on the use-case / commit helper, never on a domain class.
- **Don't** nest transactions across event publishing. If a downstream queue publish must be atomic with the DB write, use the **transactional outbox pattern** (§8.3 footnote).

---

## 8. Domain event orchestration

### 8.1 The orchestrator

```java
@Component
@RequiredArgsConstructor
public class DomainEventOrchestrator {
    private final List<DomainEventProcessor<? extends DomainEvent>> processors;

    public void process(DomainAggregate aggregate) {
        var events = aggregate.getDomainEvents();
        events.forEach(this::dispatch);
        aggregate.clearDomainEvents();
    }

    private void dispatch(DomainEventWithHeaders event) {
        processors.stream()
            .filter(p -> p.handlesType(event.domainEvent().getClass()))
            .forEach(p -> p.dispatch(event.domainEvent(), event.headers()));
    }
}
```

### 8.2 Processor contract

```java
public interface DomainEventProcessor<T extends DomainEvent> {
    void process(T event, DomainEventHeaders headers);
    boolean handlesType(Class<? extends DomainEvent> clazz);

    @SuppressWarnings("unchecked")
    default void dispatch(DomainEvent event, DomainEventHeaders headers) {
        process((T) event, headers);
    }
}
```

- One processor per `(eventType, side-effect)` pair e.g., `OrderPlacedNotificationProcessor`, `OrderPlacedAnalyticsProcessor`.
- For **optional** side effects (analytics, nice-to-have notifications), wrap downstream calls in try/catch and log the failure — don't propagate. Example:

```java
try {
    notificationsClient.send(payload);
} catch (SomethingException ex) {
    log.error("Failed to publish OrderPlacedDomainEvent — continuing", ex);
}
```

- For **event-delivery** side effects, let the exception propagate and rely on at-least-once redelivery from the upstream listener (or use the transactional outbox).
- Add a config-driven enable/disable flag per processor for safe rollout.

### 8.3 Event header propagation
- Pass trace ID, actor, etc. as **event headers** alongside the event payload — keeps the domain event itself focused on business state.
- **Headers flow through the orchestrator** → processor → outbound publisher.
- **Transactional outbox:** If you need at-most-once-and-eventually-delivered semantics for outbound events, write the event payload to an `outbox` table inside the same transaction, then background-poll + delete from the outbox. This trades latency for delivery guarantees and avoids the publish-then-crash bug.

---

## 9. Error handling

### 9.1 Domain exceptions
- Throw **domain-meaningful runtime exceptions** from the domain (`IllegalStateException` for invariant violations is acceptable; custom types like `RecordAlreadyExists`, `OrderNotFoundException` for application-layer signals).
- **Never throw** HTTP / framework exceptions from inside the domain.

### 9.2 Global exception handler
One `@RestControllerAdvice` at `@Ordered.HIGHEST_PRECEDENCE`:

```java
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CustomExceptionHandler {

    @ExceptionHandler(RecordAlreadyExistsException.class)
    public ResponseEntity<WrappedResponse<?>> handleConflict(
        RecordAlreadyExistsException ex, HttpServletRequest req) {
        log.warn("Record already exists [path:{}]", req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(WrappedResponse.error("RECORD_ALREADY_EXISTS", ex.getMessage()));
    }

    @ExceptionHandler(DownstreamException.class)
    public ResponseEntity<WrappedResponse<?>> handleDownstream(
        DownstreamException ex, HttpServletRequest req) {
        var status = ex.getStatusCode().is5xxServerError() ? HttpStatus.INTERNAL_SERVER_ERROR : ex.getStatusCode();
        log.error("Downstream error [path:{}]", req.getRequestURI(), ex);
        return ResponseEntity.status(status)
            .body(WrappedResponse.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<WrappedResponse<?>> handleValidation(
        MethodArgumentNotValidException ex, HttpServletRequest req) {
        log.warn("Validation error [path:{}]", req.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(WrappedResponse.error("VALIDATION_ERROR", ex.getMessage()));
    }
}
```

- Map each domain/integration exception to an explicit HTTP status + error code.
- Always log with request path + correlation (MDC will carry `traceId`, etc. set by the filter).
- Never return raw stack traces in responses; return a structured error body with a stable error code.

### 9.3 What to retry vs fail-fast
- **Retry** (bounded by max attempts + total duration via Resilience4j config, §7.2): optimistic-lock conflicts, transient infra failures (5xx from downstream, network blips).
- **Fail-fast:** validation errors, domain invariant violations, 4xx from downstream caused by bad input.

---

## 10. Observability — what must be wired from day one

Avoid coupling to a specific vendor (Dynatrace, NewRelic, Datadog) — wire the **abstractions** so the vendor can be swapped.

### 10.1 Correlation
- A `CorrelationFilter` (extending `OncePerRequestFilter`) extracts inbound headers (`x-trace-id`, `x-user-id`, `x-session-id`) and populates **MDC** at the start of each request, and clears it in `finally`.
- Virtual threads carry MDC like platform threads when **Micrometer Context Propagation** is on the classpath — no special wiring needed beyond enabling virtual threads (§3).
- For background work submitted via `Executors.newVirtualThreadPerTaskExecutor()`, copy the MDC manually (`MDC.getCopyOfContextMap()`) into the submitted task — virtual threads do not inherit MDC automatically across thread boundaries.

```java
public class CorrelationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
        try {
            MDC.put("traceId", Optional.ofNullable(req.getHeader("x-trace-id"))
                .orElseGet(() -> UUID.randomUUID().toString()));
            Optional.ofNullable(req.getHeader("x-user-id")).ifPresent(v -> MDC.put("userId", v));
            Optional.ofNullable(req.getHeader("x-session-id")).ifPresent(v -> MDC.put("sessionId", v));
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
        }
    }
}
```

### 10.2 Structured logging
- JSON log encoder, one log **event** per log line.
- Every log line carries `traceId`, `userId`, `aggregateId`, `useCase` via MDC — no string concatenation of IDs into messages.
- Log at the right level: expected outcomes at `info`, recoverable anomalies at `warn`, unexpected failures at `error` with a stack trace.

### 10.3 Tracing attributes
- Define a small `TracingAttribute` enum of business-meaningful attribute keys (`userId`, `aggregateId`, `tenantId`).
- Adapter / orchestration code sets the attributes; swapping the tracing vendor only changes the implementation behind the enum.

### 10.4 Metrics
- Business-event counters for **business events** (orders placed, payments received) — not just HTTP.
- Histograms for use-case latency, broken down by outcome (success / failure / retry).
- Track **virtual-thread pinning** as a metric if you depend on third-party native code (`-Djdk.tracePinnedThreads=full` + log parser).

---

## 11. Configuration

### 11.1 Typed config properties — always

```java
@ConfigurationProperties("app.retry.transaction")
@Validated
public record JdbcRetryProperties(
    @NotNull BackoffCfg backoff) {
    public record BackoffCfg(
        @Min(1) int maxAttempts,
        @Min(100) long minBackoffMs,
        double multiplier,
        @DecimalMin("0.0") double jitter) {}
}
```

Never `@Value("${...}")` scattered across classes. One property record per concern, validated at startup.

### 11.2 Feature toggles
- Use config-driven booleans (`@ConfigurationProperties`) for safe rollout of new processors / handlers.
- Toggles live in the `@Configuration` imports, never in the domain.

### 11.3 Profiles
- `default` — production-like
- `integration-test` — wired to Testcontainers and WireMock containers (§12)
- `component-test` — wired to Testcontainers and Karate mock servers (§13)
- Local profiles must run against Testcontainers, never against shared cloud resources.
- **All profiles enable virtual threads** (`spring.threads.virtual.enabled`).

---

## 12. TDD discipline — Spring Integration tests are the inner loop

TDD is **mandatory** for this style of service. Red → green → refactor governs every feature, every bug fix, every adapter, every event processor, every config change that affects behavior. The primary test surface for the TDD loop is **running Integration tests** (`@SpringBootTest`) backed by **Testcontainers** for stateful infra and **WireMock** for downstream HTTP.

### 12.1 Why Integration tests are the TDD default — not "unit + mocks"

- **Integration-test** validates the whole **controller → use case → port → adapter → infra**. Mock-heavy unit tests pass while the seams misbehave.
- **Mocking kills the seam shape**: an in-container test catches schema drift, serialization mistakes, transaction-boundary bugs, retry miscounts, connection-pool leaks, header-propagation gaps. Mocked OB tests pass while real migrations break.
- After the first context startup, a well-seamed Spring integration test runs fast — the singleton-container pattern (§12.5) keeps the context and containers warm across the whole suite.
- The **domain layer still gets pure unit tests** (no Spring, no infra). These are fast and exhaustive — invariants, state transitions, event emission. The TDD integration-test discipline applies to anything that touches a port, an adapter, or framework wiring.

### 12.2 TDD loop

1. **Red:** Write a failing `@SpringBootTest` integration test that drives the system from its outermost driving boundary (HTTP, queue listener, scheduled trigger). Assert on the outcome that matters to a caller / downstream: response shape + DB state + log signal.
2. **Green:** Implement the minimum code to make the test pass. Inside the green step, TDD any new domain rule with a fast pure unit test.
3. **Refactor:** Rename, simplify, extract. Keep the test still green. Re-run.
4. **Next:** Move to the next scenario, the next error path, the next concurrency case.

The Karate BDD layer (§13) sits **downstream** of the TDD loop — it codifies finished behavior as acceptance / journey documentation. Karate features are not the TDD substitute; do not write them first when developing.

### 12.3 The containerised infra stack

Every external dependency runs as a real container managed by Testcontainers — **never mocked at the Java level**.

| Dependency type    | Container                                                             | Notes                                                                  |
|--------------------|-----------------------------------------------------------------------|------------------------------------------------------------------------|
| Relational DB      | `PostgreSQLContainer<?>`, `MySQLContainer`, `SpannerEmulatorContainer`| Run real migrations (Flyway / Liquibase) at container start            |
| Kafka              | `ConfluentKafkaContainer` (Testcontainers Kafka module)               | Create real topics; assert with a real blocking `kafkaConsumer`        |
| GCP Pub/Sub        | `PubSubEmulatorContainer`                                             | Create real topics + subscriptions; assert with a real subscriber      |
| Object storage     | `LocalStack S3` or `MinIOContainer`                                   | Real S3 client                                                         |
| AWS SQS / SNS / S3 | `LocalStackContainer`                                                 | Real SDK clients                                                       |
| HTTP downstream    | `WireMockContainer` (Testcontainers WireMock module)                  | One container per logical downstream; stub per test (§12.4)            |

### 12.4 WireMock-as-container — the standard for downstream HTTP

For every downstream HTTP API the service calls (REST, GraphQL, internal services, third parties), run a **WireMock container** and stub responses per test. **Do not** use `@MockBean` or in-JVM WireMock for Integration tests.

> In Cloak the running `payments` / `POST /charge` downstream maps to a **push-notification service (e.g. APNs), `POST /notify`** (§0.6.2). The assertions are the same shape; just remember the notify payload carries IDs and routing metadata, never message content (§0.6.4).

**Why containerised WireMock, not in-JVM WireMock:**
- No classpath conflict between the app's HTTP client and an embedded WireMock server.
- The same WireMock JSON stub format is used by QA and contract-testing teams — stubs can be shared between layers.
- Container isolation: WireMock runs out-of-process, reused via the singleton pattern (§12.5), so it never pollutes the application's JVM state.

**Setup pattern (a complete `@SpringBootTest` example with Web MVC + virtual threads):**

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("integration-test")
class PlaceOrderIntegrationTest extends IntegrationTestBase {

    @AfterEach void resetAll() {
        PAYMENTS.reset();
    }

    @Test
    void placeOrder_chargesPayment_persistsAndPublishesEvent() {
        // GIVEN — stub the downstream payments service
        PAYMENTS.stubFor(post(urlEqualTo("/charge"))
            .willReturn(aResponse().withStatus(200).withBody("""
                { "chargeId": "ch_123", "status": "AUTHORISED" }
                """)));

        // WHEN — hit the real HTTP endpoint via TestRestTemplate:
        var response = restTemplate.exchange(
            RequestEntity.post("/v1/orders")
                .header("x-actor", "actor-1")
                .body(samplePlaceOrderPayload()),
            new ParameterizedTypeReference<WrappedResponse<PlaceOrderResponse>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // THEN — verify DB state through the real adapter
        var saved = orders.findLatestForCustomer("cust-1").orElseThrow();
        assertThat(saved.status()).isEqualTo(OrderStatus.PLACED);

        // THEN — verify the downstream call shape (contract assertion)
        PAYMENTS.verify(postRequestedFor(urlEqualTo("/charge"))
            .withRequestBody(matchingJsonPath("$.amount", equalTo("100.00")))
            .withHeader("x-trace-id", matching(".+")));

        // THEN — verify the outbound event was published
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(kafkaConsumer.poll("orders.events"))
                .anySatisfy(rec -> assertThat(new String(rec.headers().lastHeader("eventType").value()))
                    .isEqualTo("OrderPlacedDomainEvent")));
    }

    @Test
    void downstreamPaymentDeclined_returnsConflict_noOrderPersisted() {
        PAYMENTS.stubFor(post(urlEqualTo("/charge"))
            .willReturn(aResponse().withStatus(402).withBody("""
                { "error": "DECLINED" }
                """)));

        var response = restTemplate.exchange(
            RequestEntity.post("/v1/orders")
                .header("x-actor", "actor-1")
                .body(samplePlaceOrderPayload()),
            new ParameterizedTypeReference<WrappedResponse<PlaceOrderResponse>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(orders.findLatestForCustomer("cust-1")).isEmpty();
    }
}
```

### 12.5 Singleton container pattern — keep the suite fast

Starting containers per test class is expensive. Define them as **`static final`** fields started once per JVM, and reuse:

```java
abstract class IntegrationTestBase {
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine");
    static final ConfluentKafkaContainer KAFKA =
        new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.0");
    static final WireMockContainer PAYMENTS =
        new WireMockContainer("wiremock/wiremock:3.9.1");
    static final WireMockContainer NOTIFICATIONS =
        new WireMockContainer("wiremock/wiremock:3.9.1");

    static {
        Startables.deepStart(POSTGRES, KAFKA, PAYMENTS, NOTIFICATIONS).join();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("downstream.payments.url", PAYMENTS::getBaseUrl);
        r.add("downstream.notifications.url", NOTIFICATIONS::getBaseUrl);
        r.add("spring.threads.virtual.enabled", () -> "true");    // verify VTs are on in tests too
    }

    @Autowired protected TestRestTemplate restTemplate;
    @Autowired protected OrderRepositoryPort orders;
    @Autowired protected KafkaTestConsumer kafkaConsumer;
}
```

Extend `IntegrationTestBase` from every integration test class. Spring reuses the same application context across classes that share property sources, so context startup happens **once** per **suite**. The Testcontainers reaper handles container cleanup on JVM exit.

### 12.6 Reset state between tests

Sharing containers means tests must clean up after themselves. Reset is **eager**, not per-class:

| Resource           | Reset                                                                                          |
|--------------------|------------------------------------------------------------------------------------------------|
| Database           | `TRUNCATE` all tables in `@AfterEach` (faster than transactional rollback for context-shared tests) |
| Kafka consumer     | Reset consumer group per test class, delete in `@AfterEach`                                    |
| Pub/Sub emulator   | Create per-test subscription, delete in `@AfterEach`                                           |
| WireMock           | `wireMockContainer.resetAll()` in `@AfterEach` — clears stubs **and** request journal          |
| Redis              | `FLUSHALL` in `@AfterEach`                                                                     |
| Outbox / scheduler | `TRUNCATE` in `@AfterEach`                                                                     |
| Spring scheduler   | Do **not** use `@PostConstruct` — forbidden except for genuinely unavoidable cases. Each one adds 5-20 seconds. |

### 12.7 TDD rules — non-negotiable

1. **Red first.** No production code without a failing test that requires it. If you're writing code "just in case" or "I'll cover it later," stop.
2. **Tests assert outcomes, not implementations.** Assert on the observable state of the system (response, DB row, published event) — never on internal call sequences.
3. **Integration tests use real infra in containers.** Assert the real outcome — e.g. status `PLACED` and the DB row populated — not a mocked stand-in.
4. **`@MockBean` is permitted only for genuinely external concerns that cannot be containerised** — `DomainClock`, `RandomGenerator`, a secret manager, an OS-level identity provider.
5. **Stub, not spy.** `@MockBean` replacing a repository, queue listener, or outbound HTTP client is a code smell — replace with a Testcontainers equivalent.
6. **Enable pinned-thread tracing in the test JVM** (`-Djdk.tracePinnedThreads=full`). Pinning is silent at low load and catastrophic at high load — catch it early.
7. **If the suite is slow,** suspect connection-pool exhaustion (§3.3) or a `synchronized` block pinning carriers. Check pool metrics; enable pinned-thread tracing.

### 12.8 What integration tests cover — and what unit tests still own

| Coverage                                           | Integration test (`@SpringBootTest` + Testcontainers + WireMock) | Pure unit test        |
|----------------------------------------------------|------------------------------------------------------------------|-----------------------|
| Controller routing, header parsing, validation     | ✓                                                                | ✗                     |
| Use-case orchestration end-to-end                  | ✓                                                                | ✗                     |
| Port → adapter mapping & SQL / queue serialization | ✓                                                                | ✗                     |
| Retry, optimistic-lock, transaction boundary       | ✓                                                                | ✗                     |
| Outbound event publication & headers               | ✓                                                                | ✗                     |
| Downstream HTTP contract (request shape, response handling) | ✓ via WireMock stubs + `verify()`)                    | ✗                     |
| Domain invariants & state transitions              | ✗ (covered indirectly)                                           | ✓ (exhaustive, fast)  |
| Value-object validation rules                      | ✗ (covered indirectly)                                           | ✓ (exhaustive, fast)  |
| Domain service algorithm correctness               | ✗                                                                | ✓                     |
| Mapper logic (DB → command, row → aggregate)       | ✗ covered indirectly                                             | ✓ for branchy mappers |

Don't duplicate. If an integration test already covers a case, don't add a redundant unit test. If a unit test exhaustively covers value-object validation, the integration test only needs the happy path.

### 12.9 Common TDD pitfalls — refuse them

- **"This test is slow, let me mock the database."** No. Speed up the container (pre-warmed images, `tmpfs` for Postgres data, parallel execution with separate schemas) — don't lose the fidelity.
- **"I'll add an 'if' branch and skip the tests."** No. Write the test for the branch first.
- **"WireMock keeps remembering stubs from the last test."** Call `resetAll()` in `@AfterEach` (§12.6) — it clears stubs and the request journal.
- **"The test passed locally but fails in CI."** Almost always container startup timing or a `@BeforeAll` ordering issue — start containers in the shared base class (§12.5), not per test.
- **"I don't need to assert the request body."** You do — `withRequestBody(matchingJsonPath(...))` exists because contract drift is otherwise silent.
- **Failures must point at the cause.** When a test fails, the message + container logs + captured WireMock requests should reveal the bug in under a minute. If they don't, the test needs better assertions.
- **"This integration test runs millions of concurrent virtual threads — why is it slow?"** Almost always connection-pool exhaustion (§3.3) or a `synchronized` block pinning carriers. Check pool metrics; enable pinned-thread tracing.

---

## 13. BDD layer — Karate for living documentation & journeys

The Karate BDD features sit **downstream of the TDD loop** as acceptance documentation and as the journey-test layer covering multi-step user flows. They are **complementary** to — not a substitute for — the integration tests in §12.

| Layer            | Tool                                 | Mock style                         | When written                           |
|------------------|--------------------------------------|------------------------------------|----------------------------------------|
| Unit             | JUnit 5 + AssertJ + Mockito          | stub interfaces only inside domain | During §12 green step                  |
| TDD Inner Loop   | `@SpringBootTest` + Testcontainers   | WireMock container (§12.4)         | Before any production code changes     |
| BDD payload demo | Karate                               | Karate mock-server features        | After §12 cycle complete               |
| BDD user journey | Karate                               | Karate mock-server features        | Per significant new end-to-end flow    |

### 13.1 Test pyramid summary

- **Unit tests** (`src/test`) — pure domain logic. **High volume, low cost.**
- **Integration tests** (`src/integrationTest`) — TDD inner loop; **high value, high count.** Controller → use case → port → adapter → infra.
- **Karate component / journey tests** (`src/componentTest`) — acceptance documentation; **moderate count, high readability.**
- **Contract tests** — moderate — only at boundaries with consumers/providers you don't own.

### 13.2 Karate test data philosophy — explicit over generated

For Karate features, use one folder per test case ID — explicit JSON/SQL fixtures, no shared builders, no test factories, no randomisation.

```
testcase-000/
├── 300-data.sql              # Pre-state inserted into the DB container
├── 300-payload.json          # Request body
├── 300-response.json         # Expected HTTP response
├── 300-state.json            # Expected DB state after operation
└── 300-expected-event.json   # Expected outbound event (if any)
```

Pros: each testcase is self-contained, copy-pasteable, no hidden coupling between tests. No test proliferation, no randomisation. Accept it — it pays for itself the first time you debug a flaky test.

### 13.3 Feature file shape — use cases

```gherkin
@placeOrder
Feature: Place an order
Scenario Outline: <testCase> — <scenarioDesc>
    Given def payload = karate.read(payloadFilename)
    And path "v1/orders"
    When method post
    Then status <expectedStatus>
    And match response == karate.read(expectedResponse)
    And asserter.huntForTestCase(testCase, karate.readAsString(expectedStateJson))
    And eventVerifier.assertEvent(testCase, karate.readAsString(expectedEventJson))

Examples:
| testCase | scenarioDesc       | payloadFilename  | expectedStatus | expectedResponse  | expectedStateJson | expectedEventJson |
|----------|--------------------|-----------------|----------------|-------------------|-------------------|-------------------|
| 301      | Place an order     | 301-payload.json | 200           | 301-response.json | 301-state.json    | 301-event.json    |
| 301      | duplicate order ID | 301-payload.json | 409           | 301-response.json | 301-state.json    |                   |
| 302      | database outage    | 302-payload.json |               |                   |                   |                   |
```

### 13.4 Feature file shape — user journeys

One scenario, many sequential steps. Each step asserts response shape **AND** verifies the cumulative DB / event state.

### 13.5 Verification utilities (build these once, use everywhere)

- **`StateAsserter`:** read aggregate state from the container DB, normalise UUIDs/timestamps, deep-diff against expected JSON.
- **`PubSubVerifier` / `KafkaVerifier`**: await subscription with `Awaitility`, parse payload, assert.
- **`ResponseCapture`** — capture log lines for async-completion signals ("Finished processing X"), so journey tests can wait for fire-and-forget work to land.
- **`Resetter`:** swaps in failure-injecting beans at runtime (`dataSourceOutage()`, `downstreamError(code)`, `validationProblems()`). Critical for testing retry and error paths without leaving the test harness.

### 13.6 Mock servers in the Karate layer — Karate feature mocks, not WireMock

TDD integration tests (§12) = WireMock container. Network-level fidelity for the inner dev loop; same stub format QA uses.
BDD integration tests (§13) = Karate mock-server features. Single Karate DSL across tests and mocks; programmable/stateful mock logic in feature-file form; easier for non-Java contributors to read.

The two layers are independent — they share the same mocking strategies, each appropriate to their layer's purpose. **Do not** use Karate mock outside `@SpringBootTest` integration tests, and **do not** use WireMock containers from Karate features.

```gherkin
Feature: Payments Mock Server
Scenario: postMatches('/charge') && methods('post')
    * def response = karate.read('responses/charge-authorised.json')
    * responseStatus = 200
```

Mock servers live at well-known ports defined in the `component-test` profile.

### 13.7 Chaos testing
- ToxiProxy in front of the database container. Tag chaos scenarios with `@chaos` so they can be run separately.
- Cover: `DISABLE`, `LATENCY`, `TIMEOUT`, `BANDWIDTH`, `PACKET_LOSS`.
- Use the `Resetter` utility with `@ChaosWithRecovery` pattern (`applyWithRecovery(action, ms)`) to validate retry-then-success flows in one scenario.

### 13.8 Test execution time

Aim for **entire BDD component suite** under a **minute** on a developer laptop. Integration suite (§12) similar. If either climbs higher, the team stops running it before commit and the strategy collapses.

---

## 14. CI / build gates

- `./gradlew build` runs unit + integration + component + chaos + jacoco + sonar. Build fails if any tier fails or coverage drops below threshold (≥ 85% line coverage is a reasonable starting bar for this style of architecture — domain logic should be near-100%).
- Block PR merge on build status.
- Run a separate, slower "soak" CI pipeline nightly that re-runs chaos scenarios with longer durations and parses pinned-thread traces (`-Djdk.tracePinnedThreads=full`).

---

## 15. Anti-patterns to refuse

When asked to do any of the following, **push back and propose the in-pattern alternative**. Apply §0.1 — if the user insists on an anti-pattern, ask why; there may be a genuine constraint that warrants a deliberate trade-off.

1. **"Just add a setter on the aggregate"** — Add a business method that enforces the invariant (§1.2). Setters bypass the domain model.
2. **"Put the validation in the controller"** — Validation that involves business rules belongs in the domain or a domain service (§4.5). Controllers only do shape validation (§6.3).
3. **"The code can just `Instant.now()`"** — Inject `DomainClock` (§4.6). Time-dependent code without a clock abstraction is untestable.
4. **"Use `@MockBean` for this downstream"** — Use Testcontainers (§12.4). `@MockBean` for ports defeats the purpose of integration tests.
5. **"Use `@PostConstruct` annotation in domain"** — No Spring in the domain. Wrap the transition inside a `DomainService` (§4.5).
6. **"Make the domain depend on a Spring / Jackson / JDBC type"** — No. The domain stays pure Java. Framework types are an infrastructure concern handled in `usecase/` and/or `adapter/`.
7. **"Just use a `synchronized` block here"** — Use a `ReentrantLock` (§3.3). `synchronized` pins the virtual thread to its carrier and serializes throughput under contention.
8. **"Let's return `CompletableFuture<?>` from this port for performance"** — No. Plain return types only (§6.1). Virtual threads are the concurrency story for performance.
9. **"Publish the event before saving so the entity transitions"** — No. Persist first, publish after commit (§5.2). If you need at-least-once delivery, use the **transactional outbox** pattern (§8.3).
10. **"Catch the exception in the adapter and return null"** — Return `Optional.empty()` for genuinely-missing-but-valid cases (§6.1); let unexpected failures propagate to the exception handler (§9.2).
11. **"Just use field injection / `@Autowired`"** — Constructor injection only, via `@RequiredArgsConstructor`. Field injection is untestable and hides dependencies.
12. **"We can use `var` with `Builder`"** — Use `record` + `@Builder` for input commands (§4.2). Builders hide required-field gaps.
13. **"Let's add `@Transactional` to the controller"** — No. Transaction boundary is the commit helper only (§5.2).
14. **"Let's make domain depend on `@RetryPolicy`"** — Retry logic in the domain violates Single Responsibility (§0.4 SOLID). Retries live in the application layer.
15. **"Atomic data class + service class"** — Service belongs alongside the responsibility in a separate class. Split it by concern.
16. **"Add `@DirtiesContext`"** — Fix it. `@DirtiesContext` kills suite performance.
17. **"We need a config knock-out to hide untestable behavior"** — Then verify the actual test. Do not hide untestable behavior behind config flags.
18. **"I'll extract this into a helper now in case I need it again"** — Apply the rule of three (§0.4 DRY caveat). Inline it for the first duplicate instance.
19. **"I'll add a flag so we can switch behavior later"** — YAGNI (§0.4 KISS). Don't add config knobs until the second use case actually exists.
20. **"Let me add another method to it"** — A utility class with five unrelated methods violates Single Responsibility (§0.4 SOLID). Split it by concern.
21. **"Using a concrete adapter as an extra method"** — Liskov violation (§0.4 SOLID-L). Either the method belongs on the port, or it doesn't belong in the caller.
22. **"ThreadLocal to carry request context into the Listener"** — MDC works for logging context (§10.1). For business context, pass it explicitly. `ThreadLocal` on virtual threads is manual.

---

## 16. Bootstrap checklist for a new service

When asked to scaffold a new service from this guide, do in order. **At each step, apply §0.1 and §0.2** — ask the user when a decision is non-obvious; validate assumptions before writing code.

1. **Confirm with the user** — the service's purpose, expected throughput (RPS), latency budget, upstream callers, downstream dependencies, data ownership, retention. Capture the answers in the README. (§0.3)
2. **Scaffold the skeleton** — Spring Boot 3.2+; configure `integrationTest` and `componentTest` as additional Gradle source sets; add Lombok, Testcontainers, WireMock, Karate, AssertJ, Mockito, Resilience4j, and ArchUnit dependencies.
3. **Enable VT** (§3.1) — link to this CLAUDE.md and confirm `spring.threads.virtual.enabled=true` in all profiles. Add `-Djdk.tracePinnedThreads=full` to the integration-test JVM args. Verify with a small test that logs `Thread.currentThread()` in a controller.
4. **Core aggregate skeleton** — one aggregate root with factory, value-object ID, version field, and at least one inner behavior class (§4). TDD the domain rules with pure unit tests first.
5. **First output port + adapter** — define the `RepositoryPort` (§6.1) and implement one adapter (§6.2), mapping persistence exceptions to `OptimisticLockingFailure` (§7.1).
6. **First integration test** — `IntegrationTestBase` with singleton Testcontainers (§12.5); drive the endpoint, stub the downstream WireMock, assert DB state + outbound event.
7. **Commit aggregate helper** — `CommitOrderAggregate` (§5.2) with `TransactionTemplate`. TDD via integration tests asserting HTTP status + response body.
8. **Add inbound event handlers** — one `EventHandler` per queue (§5.3), covering not-found, conflict, downstream-failure, and validation paths via integration tests.
9. **MDC utility filters** — `CorrelationFilter`, JSON Logback config, Micrometer Context Propagation on the classpath, business-metric counters.
10. **Declare real config** — `@ConfigurationProperties` for retry, transaction, feature toggles. Validation enabled at startup. Resilience4j Retry bean wired (§7.2).
11. **BDD Karate harness** — `ComponentTest.java` runner, `application-component-test.yml`, the verification utilities (§13.5), and a Karate mock-server feature for the same downstream(s).
12. **First BDD use-case feature** — use-case Karate feature + matching mock-server feature; both passing (§13.3).
13. **First BDD user-journey feature** — multi-step end-to-end scenario; passing (§13.4).
14. **README** — document the Web MVC + virtual-threads model, the local-run command, and each test command; link the Testcontainer + WireMock images with pinned tags. From here on, the README updates in the same commit as every change that affects how the service is built, run, configured, tested, or integrated (§0.3).
15. **CI/CD pipeline** — `./gradlew build` runs unit + integration + component + chaos + jacoco + sonar; blocks merge on failure; pinned container image tags; pinning-trace log parser on the nightly soak pipeline (§14).

After step 15, the service grows by adding feature slices that all follow the same pattern: **failing test → aggregate behavior (TDD) → port → adapter → use case → Karate acceptance feature → README update**.

---

## 17. When in doubt

- **Where does this logic go?** If it enforces a business rule — domain. If it orchestrates I/O around the domain — use case. If it translates a format — mapper. If it talks to infrastructure — adapter.
- **Should I add an abstraction?** Only when the second concrete case exists. Avoid premature interfaces; ports exist because adapters exist.
- **Sync or async?** Always sync. Virtual threads run blocking code efficiently — embrace it (§3). The only exception is a `StructuredTaskScope` or a virtual-thread `ExecutorService` for genuine parallel fan-out, and even those return plain values via `Future.get()`.
- **Should this go in a domain service?** If a downstream system, an audit log, or a side effect cares about it, yes. If it's pure internal state-keeping — aggregate. Use the single-responsibility principle.
- **Should I mock or containerize?** For TDD integration tests: containerise (Testcontainers). For Karate features: Karate mock servers (§13). `@MockBean` only for clock / RNG / secrets / OS-level concerns.
- **Should I verify this?** TDD. Always. §12 (§0.2). If you can't verify it yourself, ask (§0.1). Don't move on until it does.
- **Should I commit this README change?** Then verify it. If it no longer reflects what the service now does, update it in this commit (§0.3). Don't move on until it does.
- **Should I extract this duplication into a helper?** Only on the third real occurrence with the same axis of variation (§0.4 rule of three). Otherwise: inline.
- **To use an interface or config flag for future flexibility?** No — until the second concrete use exists (§0.4 KISS / YAGNI).

End of guide.
