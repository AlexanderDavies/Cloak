package com.cloak.server.support;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only helpers that query the {@link IntegrationTestBase#LGTM grafana/otel-lgtm} stack's
 * embedded Prometheus, Tempo, and Loki HTTP APIs to assert that a signal the app exported over OTLP
 * actually landed. Designed to be wrapped in Awaitility polling (the OTLP push + backend ingest are
 * asynchronous, so a freshly-emitted signal is not visible instantly).
 *
 * <p>Base URLs come from the running container via {@link IntegrationTestBase}'s accessors (same
 * package), so the helper needs no construction state.
 */
public final class Telemetry {

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private Telemetry() {}

  /** True if the PromQL query returns a non-empty result vector in the embedded Prometheus. */
  public static boolean metricExists(String promQl) {
    JsonNode result = promQuery(promQl).path("data").path("result");
    return result.isArray() && !result.isEmpty();
  }

  /** Raw Prometheus {@code /api/v1/query} response tree, for assertions that inspect labels. */
  public static JsonNode promQuery(String promQl) {
    String url =
        IntegrationTestBase.prometheusUrl()
            + "/api/v1/query?query="
            + URLEncoder.encode(promQl, StandardCharsets.UTF_8);
    return JSON.readTree(get(url));
  }

  /** The set of label <em>names</em> present on the series matching a PromQL selector. */
  public static JsonNode promLabelNames(String selector) {
    String url =
        IntegrationTestBase.prometheusUrl()
            + "/api/v1/labels?match%5B%5D="
            + URLEncoder.encode(selector, StandardCharsets.UTF_8);
    return JSON.readTree(get(url)).path("data");
  }

  /** The set of values Prometheus has seen for a given metric label name. */
  public static JsonNode promLabelValues(String label) {
    String url =
        IntegrationTestBase.prometheusUrl()
            + "/api/v1/label/"
            + URLEncoder.encode(label, StandardCharsets.UTF_8)
            + "/values";
    return JSON.readTree(get(url)).path("data");
  }

  /** True if any trace matches the given TraceQL query in the embedded Tempo. */
  public static boolean traceExists(String traceQl) {
    return firstTraceId(traceQl) != null;
  }

  /** The id of the first trace matching the TraceQL query, or {@code null} if none match yet. */
  public static String firstTraceId(String traceQl) {
    String url =
        IntegrationTestBase.tempoUrl()
            + "/api/search?q="
            + URLEncoder.encode(traceQl, StandardCharsets.UTF_8);
    JsonNode traces = JSON.readTree(get(url)).path("traces");
    if (!traces.isArray() || traces.isEmpty()) {
      return null;
    }
    return traces.get(0).path("traceID").asString();
  }

  /** True if Tempo holds a trace with exactly this trace id (the OTel hex trace id). */
  public static boolean traceExistsById(String traceId) {
    return getTraceById(traceId) != null;
  }

  /**
   * The full trace document for {@code traceId} (Tempo {@code /api/traces/{id}}), or {@code null}
   * if Tempo has not yet ingested it. Used to inspect span attributes (e.g. the privacy assertion).
   */
  public static JsonNode getTraceById(String traceId) {
    String url = IntegrationTestBase.tempoUrl() + "/api/traces/" + traceId;
    HttpResponse<String> response = send(url);
    if (response.statusCode() == 404) {
      return null;
    }
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "Tempo GET " + url + " failed: " + response.statusCode() + " " + response.body());
    }
    return JSON.readTree(response.body());
  }

  /**
   * The Loki log lines matching {@code logQl} (over the last hour), joined by newlines; empty
   * string if none. Use a non-empty return to assert presence and an empty return to assert
   * absence.
   */
  public static String logsContaining(String logQl) {
    long endNs = System.currentTimeMillis() * 1_000_000L;
    long startNs = endNs - Duration.ofHours(1).toNanos();
    String url =
        IntegrationTestBase.lokiUrl()
            + "/loki/api/v1/query_range?start="
            + startNs
            + "&end="
            + endNs
            + "&limit=1000&query="
            + URLEncoder.encode(logQl, StandardCharsets.UTF_8);
    JsonNode results = JSON.readTree(get(url)).path("data").path("result");
    StringBuilder lines = new StringBuilder();
    for (JsonNode stream : results) {
      for (JsonNode entry : stream.path("values")) {
        // Each entry is [ "<unix-ns>", "<log line>" ] or, when present, a third element of
        // structured metadata { trace_id, span_id, ... }. Include both so callers can match on the
        // message body or the correlated trace id, and so the privacy check covers metadata too.
        if (entry.isArray() && entry.size() >= 2) {
          lines.append(entry.get(1).asString());
          if (entry.size() >= 3) {
            lines.append(' ').append(entry.get(2).toString());
          }
          lines.append('\n');
        }
      }
    }
    return lines.toString();
  }

  private static String get(String url) {
    HttpResponse<String> response = send(url);
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "GET " + url + " failed: " + response.statusCode() + " " + response.body());
    }
    return response.body();
  }

  private static HttpResponse<String> send(String url) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
      return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (java.io.IOException e) {
      throw new IllegalStateException("HTTP request to " + url + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("HTTP request to " + url + " interrupted", e);
    }
  }
}
