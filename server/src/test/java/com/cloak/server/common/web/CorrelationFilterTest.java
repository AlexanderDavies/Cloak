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
  void mdc_isClearedAfterChain() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, res) -> {};

    filter.doFilter(request, response, chain);

    assertThat(MDC.get("traceId")).isNull();
  }
}
