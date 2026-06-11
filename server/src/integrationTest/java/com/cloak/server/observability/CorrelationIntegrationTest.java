package com.cloak.server.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.cloak.server.support.IntegrationTestBase;
import com.cloak.server.support.Telemetry;
import com.cloak.server.support.Tokens;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Proves the API correlation id is the OTel trace id, not a separate UUID: the {@code X-Trace-Id}
 * header and the envelope {@code traceId} both equal the id of a trace that actually exists in
 * Tempo. This is what makes a client-reported error reference resolve to its Grafana trace (§10.1).
 */
class CorrelationIntegrationTest extends IntegrationTestBase {

  @LocalServerPort int port;

  @Test
  void envelopeTraceId_andHeader_equalTheOtelTraceId() {
    String token = Tokens.accessToken(issuerUri(), "alice");
    EntityExchangeResult<byte[]> result =
        RestTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .build()
            .get()
            .uri("/v1/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .returnResult();

    String headerTraceId = result.getResponseHeaders().getFirst("X-Trace-Id");
    String body = new String(result.getResponseBodyContent(), StandardCharsets.UTF_8);
    assertThat(headerTraceId).isNotBlank();
    // The envelope's traceId equals the response header...
    assertThat(body).contains("\"traceId\":\"" + headerTraceId + "\"");

    // ...and that id resolves to a real exported trace in Tempo (i.e. it is the OTel trace id).
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(Telemetry.traceExistsById(headerTraceId)).isTrue());
  }
}
