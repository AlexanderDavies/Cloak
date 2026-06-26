package com.cloak.server.support;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.grafana.LgtmStackContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base class for {@code @SpringBootTest} integration tests. Boots a single shared set of
 * Testcontainers (Postgres, Kafka, Schema Registry, Keycloak) once per JVM and wires their
 * connection details into the Spring context, so every concrete test runs against real
 * infrastructure. Kafka and Schema Registry share a Docker {@link Network}: the registry reaches
 * the broker over the in-network listener {@code kafka:19092}, while tests reach the broker over
 * the mapped host listener.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
public abstract class IntegrationTestBase {

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("cloak")
          .withUsername("cloak")
          .withPassword("cloak");

  // Shared Docker network so Schema Registry can reach the Kafka broker by hostname.
  static final Network NET = Network.newNetwork();

  static final ConfluentKafkaContainer KAFKA =
      new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
          .withNetwork(NET)
          .withListener("kafka:19092"); // in-network listener consumed by Schema Registry

  // Confluent Schema Registry backs the Avro serdes; it reads/writes schemas through the broker.
  static final GenericContainer<?> SCHEMA_REGISTRY =
      new GenericContainer<>(DockerImageName.parse("confluentinc/cp-schema-registry:7.6.0"))
          .withNetwork(NET)
          .withExposedPorts(8081)
          .dependsOn(KAFKA)
          .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
          .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:19092")
          .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081");

  // Imports the seeded realm (alice/bob + cloak-test client) from iam/realm/cloak-realm.json,
  // copied onto the integrationTest classpath under realms/ by processIntegrationTestResources.
  // The cloak login theme is copied into the container so LoginThemeIntegrationTest can assert it.
  static final KeycloakContainer KEYCLOAK =
      new KeycloakContainer("quay.io/keycloak/keycloak:26.0")
          .withRealmImportFile("/realms/cloak-realm.json")
          .withCopyFileToContainer(
              MountableFile.forHostPath("../iam/themes/cloak"), "/opt/keycloak/themes/cloak");

  // All-in-one OTel Collector + Prometheus + Loki + Tempo + Grafana. The app exports OTLP here;
  // tests
  // assert metrics/traces/logs landed by querying the embedded Prometheus/Tempo/Loki HTTP APIs.
  static final LgtmStackContainer LGTM = new LgtmStackContainer("grafana/otel-lgtm:0.28.0");

  static {
    Startables.deepStart(POSTGRES, KAFKA, SCHEMA_REGISTRY, KEYCLOAK, LGTM).join();
  }

  /** Base URL of the stack's embedded Prometheus (metrics query API). */
  protected static String prometheusUrl() {
    return LGTM.getPrometheusHttpUrl();
  }

  /** Base URL of the stack's embedded Tempo (trace search API). */
  protected static String tempoUrl() {
    return LGTM.getTempoUrl();
  }

  /** Base URL of the stack's embedded Loki (log query API). */
  protected static String lokiUrl() {
    return LGTM.getLokiUrl();
  }

  /** Issuer URI of the imported {@code cloak} realm on the test Keycloak. */
  protected static String issuerUri() {
    return KEYCLOAK.getAuthServerUrl() + "/realms/cloak";
  }

  /** Host-reachable URL of the Confluent Schema Registry container. */
  protected static String schemaRegistryUrl() {
    return "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081);
  }

  /** Host-reachable Kafka bootstrap servers, for tests that consume directly off the broker. */
  protected static String kafkaBootstrapServers() {
    return KAFKA.getBootstrapServers();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    // Flyway runs against the same datasource; migrations come from ../db/migrations.
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("spring.kafka.properties.schema.registry.url", IntegrationTestBase::schemaRegistryUrl);
    r.add("spring.threads.virtual.enabled", () -> "true");
    // Resource-server issuer: both the typed config (cloak.auth) and Boot's JWT decoder.
    r.add("cloak.auth.issuer-uri", IntegrationTestBase::issuerUri);
    r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", IntegrationTestBase::issuerUri);
    // Point the OTLP exporters at the LGTM container; push metrics every second so assertions
    // don't wait on the default step.
    r.add("management.otlp.metrics.export.url", () -> LGTM.getOtlpHttpUrl() + "/v1/metrics");
    r.add(
        "management.opentelemetry.tracing.export.otlp.endpoint",
        () -> LGTM.getOtlpHttpUrl() + "/v1/traces");
    r.add(
        "management.opentelemetry.logging.export.otlp.endpoint",
        () -> LGTM.getOtlpHttpUrl() + "/v1/logs");
    r.add("management.otlp.metrics.export.step", () -> "1s");
  }
}
