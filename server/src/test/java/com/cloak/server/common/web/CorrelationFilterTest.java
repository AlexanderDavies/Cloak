package com.cloak.server.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationFilterTest {

  // A tracer with no active span exercises the fallback path (inbound header / generated UUID).
  private final Tracer noSpanTracer = mock(Tracer.class);
  private final CorrelationFilter filter = new CorrelationFilter(noSpanTracer);

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void activeSpan_traceIdWins_overInboundHeaderAndUuid() throws Exception {
    Tracer tracer = mock(Tracer.class);
    Span span = mock(Span.class);
    TraceContext context = mock(TraceContext.class);
    when(tracer.currentSpan()).thenReturn(span);
    when(span.context()).thenReturn(context);
    when(context.traceId()).thenReturn("0af7651916cd43dd8448eb211c80319c");
    CorrelationFilter tracingFilter = new CorrelationFilter(tracer);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Trace-Id", "client-supplied"); // must be ignored in favour of the span id
    MockHttpServletResponse response = new MockHttpServletResponse();

    tracingFilter.doFilter(request, response, (req, res) -> {});

    assertThat(response.getHeader("X-Trace-Id")).isEqualTo("0af7651916cd43dd8448eb211c80319c");
  }

  @Test
  void noSpan_inboundHeader_isHonoured_andEchoedOnResponse() throws Exception {
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
  void noSpan_absentHeader_generatesUuid() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    String[] mdcDuringChain = new String[1];
    FilterChain chain = (req, res) -> mdcDuringChain[0] = MDC.get("traceId");

    filter.doFilter(request, response, chain);

    assertThat(mdcDuringChain[0]).isNotBlank();
    assertThat(response.getHeader("X-Trace-Id")).isEqualTo(mdcDuringChain[0]);
  }

  @Test
  void noSpan_overlongInboundHeader_isRejected_andUuidGenerated() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Trace-Id", "x".repeat(65));
    MockHttpServletResponse response = new MockHttpServletResponse();
    String[] mdc = new String[1];
    FilterChain chain = (req, res) -> mdc[0] = MDC.get("traceId");

    filter.doFilter(request, response, chain);

    assertThat(mdc[0]).hasSize(36).isNotEqualTo("x".repeat(65)); // generated UUID, not the input
  }

  @Test
  void noSpan_inboundHeaderWithIllegalChars_isRejected_andUuidGenerated() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Trace-Id", "bad value!");
    MockHttpServletResponse response = new MockHttpServletResponse();
    String[] mdc = new String[1];
    FilterChain chain = (req, res) -> mdc[0] = MDC.get("traceId");

    filter.doFilter(request, response, chain);

    assertThat(mdc[0]).isNotEqualTo("bad value!").matches("[0-9a-f-]{36}");
  }

  @Test
  void noSpan_mdc_isClearedAfterChain() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, res) -> {};

    filter.doFilter(request, response, chain);

    assertThat(MDC.get("traceId")).isNull();
  }
}
