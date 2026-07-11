package com.cloak.server.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Telemetry;
import com.cloak.server.usecase.RouteMessageCommand;
import com.cloak.server.usecase.RouteMessageUseCase;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Asserts a custom use-case metric is exported over OTLP and queryable in the stack's Prometheus.
 * Routing one message must increment {@code cloak_messages_routed_total}. The recipient {@code sub}
 * is never a metric label (privacy — asserted in {@code TelemetryPrivacyIntegrationTest}).
 */
class MetricsIntegrationTest extends IntegrationTestBase {

  @Autowired RouteMessageUseCase routeMessage;

  @Test
  void routedCounter_isExportedToPrometheusOverOtlp() {
    routeMessage.route(
        new RouteMessageCommand(
            UUID.randomUUID().toString(), "alice", "bob", 1, 1, 2, new byte[] {1, 2, 3}));

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> assertThat(Telemetry.metricExists("cloak_messages_routed_total")).isTrue());
  }
}
