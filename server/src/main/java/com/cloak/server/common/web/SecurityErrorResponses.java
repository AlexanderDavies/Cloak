package com.cloak.server.common.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serialises a {@link WrappedResponse} error envelope onto a raw {@link HttpServletResponse}, for
 * the Spring Security entry point / access-denied handler that run before the dispatcher. Sets the
 * status, JSON content type, and ensures the {@code X-Trace-Id} header is present (the {@code
 * CorrelationFilter} normally sets it first, but this is a defensive backstop).
 */
final class SecurityErrorResponses {

  private SecurityErrorResponses() {}

  static void write(HttpServletResponse response, JsonMapper jsonMapper, int status, ApiError error)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    String traceId = MDC.get(CorrelationFilter.TRACE_ID_KEY);
    if (traceId != null) {
      response.setHeader(CorrelationFilter.TRACE_ID_HEADER, traceId);
    }
    jsonMapper.writeValue(response.getWriter(), WrappedResponse.error(error));
  }
}
