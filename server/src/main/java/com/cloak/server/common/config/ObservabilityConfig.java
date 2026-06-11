package com.cloak.server.common.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the cross-cutting observability beans (ARCHITECTURE_GUIDE §10). The {@link ObservedAspect}
 * makes Micrometer's {@code @Observed} annotation produce a timer metric plus a span around the
 * annotated method — used on use cases for latency + tracing without hand-rolled instrumentation.
 * Lives in the coverage-excluded {@code config} package.
 */
@Configuration
public class ObservabilityConfig {

  /** Enables {@code @Observed} method instrumentation (timer + span) via Spring AOP. */
  @Bean
  ObservedAspect observedAspect(ObservationRegistry registry) {
    return new ObservedAspect(registry);
  }

  /**
   * Attaches the OpenTelemetry Logback appender to the root logger so application logs ship over
   * OTLP to Loki, each carrying the active OTel trace id (from the OTel context) for trace
   * correlation (§10.2). Wiring it programmatically — rather than via {@code logback-spring.xml} —
   * leaves Boot's auto-configured structured (ECS) console intact.
   */
  @Bean
  InitializingBean openTelemetryLogbackAppender(OpenTelemetry openTelemetry) {
    return () -> {
      LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
      OpenTelemetryAppender appender = new OpenTelemetryAppender();
      appender.setOpenTelemetry(openTelemetry);
      appender.setContext(loggerContext);
      appender.start();
      loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    };
  }
}
