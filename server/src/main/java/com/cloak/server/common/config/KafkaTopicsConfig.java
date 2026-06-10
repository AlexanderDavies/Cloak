package com.cloak.server.common.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares Kafka topics so Spring's {@code KafkaAdmin} creates them idempotently on startup. Local
 * defaults per {@code queue/CLAUDE.md}: 12 partitions, replication factor 1.
 */
@Configuration
public class KafkaTopicsConfig {

  @Bean
  NewTopic outbound() {
    return TopicBuilder.name("cloak.messages.outbound").partitions(12).replicas(1).build();
  }
}
