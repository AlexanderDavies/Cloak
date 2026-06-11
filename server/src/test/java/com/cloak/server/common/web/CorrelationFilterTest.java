package com.cloak.server.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationFilterTest {

  private final CorrelationFilter filter = new CorrelationFilter();

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void inboundHeader_isHonoured_andEchoedOnResponse() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Trace-Id", "inbound-trace");
    MockHttpServletResponse response = new MockHttpServletResponse();
    String[] mdcDuringChain = new String[1];
    FilterChain chain = (req, res) -> mdcDuringChain[0] = MDC.get("traceId");

    filter.doFilter(request, response, chain);

    assertThat(mdcDuringChain[0]).isEqualTo("inbound-trace");
    assertThat(response.getHeader("X-Trace-Id")).isEqualTo("inbound-trace");
  }

  @Test
  void absentHeader_generatesUuid() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    String[] mdcDuringChain = new String[1];
    FilterChain chain = (req, res) -> mdcDuringChain[0] = MDC.get("traceId");

    filter.doFilter(request, response, chain);

    assertThat(mdcDuringChain[0]).isNotBlank();
    assertThat(response.getHeader("X-Trace-Id")).isEqualTo(mdcDuringChain[0]);
  }

  @Test
  void blankHeader_generatesUuid() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Trace-Id", "   ");
    MockHttpServletResponse response = new MockHttpServletResponse();
    String[] mdcDuringChain = new String[1];
    FilterChain chain = (req, res) -> mdcDuringChain[0] = MDC.get("traceId");

    filter.doFilter(request, response, chain);

    assertThat(mdcDuringChain[0]).isNotBlank().isNotEqualTo("   ");
  }

  @Test
  void overlongInboundHeader_isRejected_andUuidGenerated() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Trace-Id", "x".repeat(65));
    MockHttpServletResponse response = new MockHttpServletResponse();
    String[] mdc = new String[1];
    FilterChain chain = (req, res) -> mdc[0] = MDC.get("traceId");

    filter.doFilter(request, response, chain);

    assertThat(mdc[0]).hasSize(36).isNotEqualTo("x".repeat(65)); // generated UUID, not the input
  }

  @Test
  void inboundHeaderWithIllegalChars_isRejected_andUuidGenerated() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Trace-Id", "bad value!");
    MockHttpServletResponse response = new MockHttpServletResponse();
    String[] mdc = new String[1];
    FilterChain chain = (req, res) -> mdc[0] = MDC.get("traceId");

    filter.doFilter(request, response, chain);

    assertThat(mdc[0]).isNotEqualTo("bad value!").matches("[0-9a-f-]{36}");
  }

  @Test
  void mdc_isClearedAfterChain() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, res) -> {};

    filter.doFilter(request, response, chain);

    assertThat(MDC.get("traceId")).isNull();
  }
}
