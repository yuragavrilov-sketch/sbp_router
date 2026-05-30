package ru.copperside.sbprouter.manifest;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Verifies the manifest-event @KafkaListener is wired (fails if @EnableKafka is missing)
 * and that an event triggers ManifestPoller.poll(). The poller is mocked so no real HTTP fetch occurs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EmbeddedKafka(partitions = 1, topics = "sbp-router-manifest")
class ManifestEventConsumerIT {

    private static final String TOPIC = "sbp-router-manifest";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("sbp-router.manifest.enabled", () -> "true");
        registry.add("sbp-router.manifest.base-url", () -> "http://localhost:1");
        registry.add("sbp-router.kafka.bootstrap-servers",
                () -> System.getProperty("spring.embedded.kafka.brokers"));
        registry.add("sbp-router.manifest.event-topic", () -> TOPIC);
        registry.add("spring.cloud.config.enabled", () -> "false");
        registry.add("spring.cloud.vault.enabled", () -> "false");
    }

    @MockitoBean
    ManifestPoller poller;

    @Autowired
    KafkaListenerEndpointRegistry listenerRegistry;

    private KafkaProducer<String, byte[]> producer;

    @AfterEach
    void cleanup() {
        if (producer != null) {
            producer.close();
        }
    }

    @Test
    void eventTriggersPollerPoll() {
        var containers = listenerRegistry.getListenerContainers().stream().toList();
        assertThat(containers)
                .as("@KafkaListener container must be registered — fails if @EnableKafka is missing")
                .isNotEmpty();
        for (MessageListenerContainer c : containers) {
            ContainerTestUtils.waitForAssignment(c, 1);
        }

        String brokers = System.getProperty("spring.embedded.kafka.brokers");
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName()));
        producer.send(new ProducerRecord<>(TOPIC, "7",
                "{\"version\":7,\"checksum\":\"sha256:abc\"}".getBytes(StandardCharsets.UTF_8)));
        producer.flush();

        verify(poller, timeout(10_000)).poll();
    }
}
