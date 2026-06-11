package com.cloak.server.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.awaitility.Awaitility.await;

import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Telemetry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Regression guard for the OpenTelemetry Logback appender ↔ opentelemetry-api alignment. A log
 * event that carries a {@code Throwable} (every {@code log.error(msg, ex)}) must flow through the
 * appender without error and reach Loki. A newer appender calls {@code
 * LogRecordBuilder.setException(..)} which the api version Boot manages lacks — throwing {@code
 * NoSuchMethodError} (an {@link Error}, so Logback's appender guard does not swallow it) on the
 * logging thread. The plain {@code log.warn} coverage elsewhere does not exercise this path.
 */
class LoggingExceptionIntegrationTest extends IntegrationTestBase {

  private static final Logger log = LoggerFactory.getLogger(LoggingExceptionIntegrationTest.class);

  @Test
  void errorLogWithThrowable_shipsToLoki_withoutThrowing() {
    String marker = "telemetry-exc-" + UUID.randomUUID();

    assertThatNoException()
        .isThrownBy(
            () ->
                log.error(
                    "observability exception-path probe {}", marker, new RuntimeException("boom")));

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () ->
                assertThat(
                        Telemetry.logsContaining(
                            "{service_name=\"cloak-server\"} |= \"" + marker + "\""))
                    .contains(marker));
  }
}
