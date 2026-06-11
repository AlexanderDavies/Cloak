package com.cloak.server.common.config;

import com.cloak.server.adapter.output.kafka.message.OutboundEnvelope;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Provides a typed {@code KafkaTemplate<String, OutboundEnvelope>} for the message publisher.
 * Spring Boot's auto-configured template is {@code KafkaTemplate<Object, Object>}, which does not
 * satisfy the adapter's generic injection point, so a dedicated factory + template are declared
 * here. Both inherit the resolved {@code spring.kafka.producer.*} properties (Avro serializers,
 * registry URL).
 */
@Configuration
public class KafkaProducerConfig {

  @Bean
  ProducerFactory<String, OutboundEnvelope> envelopeProducerFactory(KafkaProperties properties) {
    return new DefaultKafkaProducerFactory<>(properties.buildProducerProperties());
  }

  @Bean
  KafkaTemplate<String, OutboundEnvelope> envelopeKafkaTemplate(
      ProducerFactory<String, OutboundEnvelope> factory) {
    KafkaTemplate<String, OutboundEnvelope> template = new KafkaTemplate<>(factory);
    // This template is hand-built, so spring.kafka.template.observation-enabled (which only affects
    // Boot's auto-configured template) does not reach it. Enable it explicitly so the producer span
    // joins the message trace (§10); the ObservationRegistry is resolved from the context.
    template.setObservationEnabled(true);
    return template;
  }
}
