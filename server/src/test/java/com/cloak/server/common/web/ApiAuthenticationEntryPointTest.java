package com.cloak.server.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import tools.jackson.databind.json.JsonMapper;

class ApiAuthenticationEntryPointTest {

  private final ApiAuthenticationEntryPoint entryPoint =
      new ApiAuthenticationEntryPoint(new JsonMapper());

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void noAuth_returns401_unauthorizedEnvelope() throws Exception {
    MDC.put("traceId", "trace-abc");
    MockHttpServletResponse response = new MockHttpServletResponse();

    entryPoint.commence(
        new MockHttpServletRequest(), response, new InsufficientAuthenticationException("nope"));

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(response.getContentType()).contains("application/json");
    assertThat(response.getHeader("X-Trace-Id")).isEqualTo("trace-abc");
    assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    assertThat(response.getContentAsString()).contains("\"traceId\":\"trace-abc\"");
  }

  @Test
  void noTraceIdInMdc_omitsHeader_stillWritesEnvelope() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    entryPoint.commence(
        new MockHttpServletRequest(), response, new InsufficientAuthenticationException("nope"));

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(response.getHeader("X-Trace-Id")).isNull();
    assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
  }
}
