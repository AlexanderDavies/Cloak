package com.cloak.server.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates MDC and the response with a correlation id for every request. Reads an inbound {@code
 * X-Trace-Id} header or generates a fresh UUID, puts it in MDC under {@code traceId}, echoes it on
 * the {@code X-Trace-Id} response header, and clears MDC in {@code finally}.
 *
 * <p>Minimal down-payment on ARCHITECTURE_GUIDE §10.1 — full observability (userId/sessionId, JSON
 * encoder, tracing attributes) lands in a later plan.
 */
public class CorrelationFilter extends OncePerRequestFilter {

  /** Header carrying the correlation id on both inbound requests and outbound responses. */
  public static final String TRACE_ID_HEADER = "X-Trace-Id";

  /** MDC key under which the correlation id is stored. */
  public static final String TRACE_ID_KEY = "traceId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      String traceId =
          Optional.ofNullable(request.getHeader(TRACE_ID_HEADER))
              .filter(header -> !header.isBlank())
              .orElseGet(() -> UUID.randomUUID().toString());
      MDC.put(TRACE_ID_KEY, traceId);
      response.setHeader(TRACE_ID_HEADER, traceId);
      chain.doFilter(request, response);
    } finally {
      MDC.clear();
    }
  }
}
