package com.cloak.server.common.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes the API correlation id the OTel trace id, so a client-reported {@code X-Trace-Id} resolves
 * to the exact trace in Grafana/Tempo (ARCHITECTURE_GUIDE §10.1).
 *
 * <p>When an OTel span is active (every traced HTTP request), its trace id <em>is</em> the
 * correlation id: Micrometer's MDC bridge already exposes it under {@code traceId} for the response
 * envelope and the logs, and this filter mirrors it onto the {@code X-Trace-Id} response header.
 * The filter is ordered to run after Boot's HTTP observation filter (which opens the span) but
 * before Spring Security, so even 401/403 responses carry the trace id.
 *
 * <p>Only when no span is active (a path that bypasses HTTP tracing) does it fall back to a
 * validated inbound {@code X-Trace-Id} or a generated UUID, owning the MDC entry for the error
 * envelope.
 */
public class CorrelationFilter extends OncePerRequestFilter {

  /** Header carrying the correlation id on both inbound requests and outbound responses. */
  public static final String TRACE_ID_HEADER = "X-Trace-Id";

  /** MDC key under which the correlation id is stored (matches Micrometer's tracing MDC bridge). */
  public static final String TRACE_ID_KEY = "traceId";

  /**
   * Accept an inbound id only if it is a short, safe token. An unbounded or odd client value would
   * otherwise be echoed into MDC, every log line, the response header, and every error body.
   */
  private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  private final Tracer tracer;

  /**
   * Creates the filter.
   *
   * @param tracer Micrometer tracer used to read the active span's trace id
   */
  public CorrelationFilter(Tracer tracer) {
    this.tracer = tracer;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Span span = tracer.currentSpan();
    if (span != null) {
      // The span's trace id is the correlation id; the MDC bridge owns the MDC entry within the
      // observation scope, so only the response header needs setting here.
      response.setHeader(TRACE_ID_HEADER, span.context().traceId());
      chain.doFilter(request, response);
      return;
    }
    try {
      String inbound = request.getHeader(TRACE_ID_HEADER);
      String traceId =
          inbound != null && SAFE_TRACE_ID.matcher(inbound).matches()
              ? inbound
              : UUID.randomUUID().toString();
      MDC.put(TRACE_ID_KEY, traceId);
      response.setHeader(TRACE_ID_HEADER, traceId);
      chain.doFilter(request, response);
    } finally {
      MDC.remove(TRACE_ID_KEY);
    }
  }
}
