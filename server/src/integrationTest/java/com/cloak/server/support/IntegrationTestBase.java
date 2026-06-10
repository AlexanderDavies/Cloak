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

/**
 * Base class for {@code @SpringBootTest} integration tests. Boots a single shared set of
 * Testcontainers (Postgres, Kafka, Keycloak) once per JVM and wires their connection details into
 * the Spring context, so every concrete test runs against real infrastructure.
 */
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

  // Deliberately pre-wired for Plan 2 (JWT validation); no Phase 0 test exercises it yet.
  static final KeycloakContainer KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.0");

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
