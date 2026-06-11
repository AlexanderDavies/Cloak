package com.cloak.server.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Telemetry;
import com.cloak.server.support.Tokens;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Asserts application logs are shipped to Loki over OTLP and correlated to their trace. An
 * authenticated request to an unknown path triggers a {@code WARN} in {@code
 * GlobalExceptionHandler} within the request's trace context; that log line must be queryable in
 * Loki carrying the same trace id the client received in {@code X-Trace-Id} (§10.2 — logs
 * correlated to traces).
 */
class LoggingIntegrationTest extends IntegrationTestBase {

  @LocalServerPort int port;

  @Test
  void requestLog_isShippedToLoki_correlatedToItsTraceId() {
    String token = Tokens.accessToken(issuerUri(), "alice");
    EntityExchangeResult<byte[]> result =
        RestTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .build()
            .get()
            .uri("/v1/does-not-exist")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .returnResult();
    String traceId = result.getResponseHeaders().getFirst("X-Trace-Id");
    assertThat(traceId).isNotBlank();

    // Loki carries the OTel trace id on each log record; filter by it to prove the WARN line is
    // correlated to exactly this request's trace.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () ->
                assertThat(
                        Telemetry.logsContaining(
                            "{service_name=\"cloak-server\"} | trace_id=\"" + traceId + "\""))
                    .contains("No resource found"));
  }
}
