package com.cloak.server.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exposes a {@link TransactionTemplate} bean. Spring Boot auto-configures the {@link
 * PlatformTransactionManager} (via JPA auto-configuration) but does not register a {@code
 * TransactionTemplate} bean; we declare it here for injection into use cases that need explicit
 * transaction demarcation (e.g. persist-then-publish per ARCHITECTURE_GUIDE §5.2).
 */
@Configuration
public class PersistenceConfig {

  @Bean
  TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
    return new TransactionTemplate(tm);
  }
}
