package com.cloak.server.common.config;

import com.cloak.server.common.web.ApiAccessDeniedHandler;
import com.cloak.server.common.web.ApiAuthenticationEntryPoint;
import com.cloak.server.common.web.CorrelationFilter;
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
   * Runs the {@link CorrelationFilter} before Spring Security's filter chain (order -100) so MDC
   * {@code traceId} and the {@code X-Trace-Id} header are set even for 401/403 responses.
   */
  @Bean
  FilterRegistrationBean<CorrelationFilter> correlationFilterRegistration() {
    FilterRegistrationBean<CorrelationFilter> registration =
        new FilterRegistrationBean<>(new CorrelationFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
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
