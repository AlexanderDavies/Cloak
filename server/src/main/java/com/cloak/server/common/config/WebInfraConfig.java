package com.cloak.server.common.config;

import com.cloak.server.common.web.ApiAccessDeniedHandler;
import com.cloak.server.common.web.ApiAuthenticationEntryPoint;
import com.cloak.server.common.web.CorrelationFilter;
import io.micrometer.tracing.Tracer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wiring for the shared web infrastructure (correlation, error envelope). Registration only — no
 * logic lives here (this package is excluded from the coverage denominator).
 */
@Configuration
public class WebInfraConfig {

  /**
   * Runs the {@link CorrelationFilter} just after Boot's HTTP observation filter ({@code
   * HIGHEST_PRECEDENCE + 1}), which opens the OTel span, and before Spring Security's filter chain
   * (order -100). This ordering lets the filter read the active span's trace id while still setting
   * the {@code X-Trace-Id} header ahead of any 401/403 response.
   */
  @Bean
  FilterRegistrationBean<CorrelationFilter> correlationFilterRegistration(Tracer tracer) {
    FilterRegistrationBean<CorrelationFilter> registration =
        new FilterRegistrationBean<>(new CorrelationFilter(tracer));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
    return registration;
  }

  @Bean
  ApiAuthenticationEntryPoint apiAuthenticationEntryPoint(JsonMapper jsonMapper) {
    return new ApiAuthenticationEntryPoint(jsonMapper);
  }

  @Bean
  ApiAccessDeniedHandler apiAccessDeniedHandler(JsonMapper jsonMapper) {
    return new ApiAccessDeniedHandler(jsonMapper);
  }
}
