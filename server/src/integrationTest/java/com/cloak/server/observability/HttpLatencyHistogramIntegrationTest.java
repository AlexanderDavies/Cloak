package com.cloak.server.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Telemetry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/**
 * The HTTP server timer must publish a percentile histogram — real {@code le} buckets, not just the
 * {@code +Inf} total — so the dashboard's p50/p95 latency panel resolves via {@code
 * histogram_quantile}. Without {@code management.metrics.distribution.percentiles-histogram} the
 * only exported bucket is {@code +Inf}, and the quantile evaluates to {@code NaN} (an empty graph).
 */
class HttpLatencyHistogramIntegrationTest extends IntegrationTestBase {

  @LocalServerPort int port;

  @Test
  void httpServerTimer_exportsDistributionBuckets() {
    // Any HTTP request records the http.server.requests timer (a 401 is fine for this).
    RestTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .build()
        .get()
        .uri("/v1/me")
        .exchange()
        .expectStatus()
        .isUnauthorized();

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(hasRealLatencyBucket()).isTrue());
  }

  private static boolean hasRealLatencyBucket() {
    JsonNode result =
        Telemetry.promQuery("http_server_requests_milliseconds_bucket").path("data").path("result");
    for (JsonNode series : result) {
      String le = series.path("metric").path("le").asString();
      if (!le.isEmpty() && !"+Inf".equals(le)) {
        return true;
      }
    }
    return false;
  }
}
