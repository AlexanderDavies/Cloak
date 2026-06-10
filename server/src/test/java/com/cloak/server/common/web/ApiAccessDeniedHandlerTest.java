package com.cloak.server.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;

class ApiAccessDeniedHandlerTest {

  private final ApiAccessDeniedHandler handler = new ApiAccessDeniedHandler(new JsonMapper());

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void accessDenied_returns403_forbiddenEnvelope() throws Exception {
    MDC.put("traceId", "trace-xyz");
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    assertThat(response.getContentType()).contains("application/json");
    assertThat(response.getHeader("X-Trace-Id")).isEqualTo("trace-xyz");
    assertThat(response.getContentAsString()).contains("\"code\":\"FORBIDDEN\"");
  }
}
