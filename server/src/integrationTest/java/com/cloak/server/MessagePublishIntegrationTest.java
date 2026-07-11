package com.cloak.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.cloak.server.adapter.output.kafka.message.OutboundEnvelope;
import com.cloak.server.domain.message.Ciphertext;
import com.cloak.server.domain.message.Message;
import com.cloak.server.domain.message.MessageId;
import com.cloak.server.port.output.message.MessagePublisherPort;
import com.cloak.server.support.IntegrationTestBase;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MessagePublishIntegrationTest extends IntegrationTestBase {

  @Autowired MessagePublisherPort publisher;

  @Test
  void publishedMessage_isAvroEnvelope_keyedByRecipient() {
    byte[] cipher = {1, 2, 3};
    publisher.publish(
        Message.create(
            new MessageId("44444444-4444-4444-4444-444444444444"),
            "alice-sub",
            "bob-sub",
            1,
            1,
            3,
            new Ciphertext(cipher)));

    var props =
        Map.<String, Object>of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            kafkaBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG,
            "test-verify",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            "schema.registry.url",
            schemaRegistryUrl(),
            KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG,
            true);
    var valueDeserializer = new KafkaAvroDeserializer();
    valueDeserializer.configure(props, false);
    try (var consumer =
        new KafkaConsumer<String, Object>(props, new StringDeserializer(), valueDeserializer)) {
      consumer.subscribe(List.of("cloak.messages.outbound"));
      await()
          .atMost(Duration.ofSeconds(15))
          .untilAsserted(
              () -> {
                var records = consumer.poll(Duration.ofMillis(500));
                assertThat(records.count()).isGreaterThan(0);
                records.forEach(
                    r -> {
                      assertThat(r.key()).isEqualTo("bob-sub");
                      var env = (OutboundEnvelope) r.value();
                      assertThat(env.getCiphertext().array()).containsExactly(cipher);
                      assertThat(env.getFromSub().toString()).isEqualTo("alice-sub");
                      assertThat(env.getToDeviceId()).isEqualTo(1);
                      assertThat(env.getFromDeviceId()).isEqualTo(1);
                      assertThat(env.getMessageType()).isEqualTo(3);
                    });
              });
    }
  }
}
