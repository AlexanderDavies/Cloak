package com.cloak.server.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Registers {@link KeycloakAdminProperties} with Spring Boot's typed configuration binding and
 * provides the {@link RestClient} the Keycloak Admin adapter talks through. Lives in the
 * coverage-excluded {@code config} package — registration only, no logic.
 */
@Configuration
@EnableConfigurationProperties(KeycloakAdminProperties.class)
public class KeycloakAdminConfig {

  /**
   * The {@link RestClient} used by {@code KeycloakUserDirectoryAdapter}. Defined here (rather than
   * built inside the adapter) so the collaborator is injected — the adapter stays constructor-only
   * per {@code server/CLAUDE.md} §0.4, and tests can supply a {@code MockRestServiceServer}-bound
   * client. This app has no auto-configured {@code RestClient.Builder} bean, so we own it.
   *
   * @return a plain {@link RestClient}; per-request URIs and the bearer header are set by the
   *     adapter
   */
  @Bean
  RestClient keycloakAdminRestClient() {
    return RestClient.create();
  }
}
