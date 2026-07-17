package com.cloak.server.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Registers {@link KeycloakAdminProperties} and the {@link RestClient} the Keycloak Admin adapter
 * talks through. Retry and circuit-breaker behaviour are declarative ({@code @Retry} /
 * {@code @CircuitBreaker} on {@code KeycloakAdminClient}, configured under {@code resilience4j.*}
 * in application.yml, ARCHITECTURE_GUIDE §7.4) — this config only supplies the transport.
 */
@Configuration
@EnableConfigurationProperties(KeycloakAdminProperties.class)
public class KeycloakAdminConfig {

  @Bean
  RestClient keycloakAdminRestClient() {
    // Finite timeouts are what make a hung dependency retryable/breakable rather than a stuck
    // thread,
    // and they are also the bound on total latency (ARCHITECTURE_GUIDE §7.4): with no TimeLimiter,
    // the worst case per lookup is read-timeout (3s) × max-attempts (3) per call — a token grant
    // and
    // an exact-match search answer in well under a second, so 3s is generous — and the circuit
    // breaker cuts sustained outages short.
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(3));
    return RestClient.builder().requestFactory(factory).build();
  }
}
