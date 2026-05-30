package ru.copperside.sbprouter.manifest;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer wiring for the routing "manifest published" event. @EnableKafka is REQUIRED
 * (Spring Boot 4 does not auto-enable @KafkaListener). Active only when the dynamic-routing
 * feature is on (sbp-router.manifest.enabled=true); reuses sbp-router.kafka.bootstrap-servers.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "sbp-router.manifest", name = "enabled", havingValue = "true")
@EnableKafka
public class ManifestEventKafkaConfig {

    @Bean
    ConsumerFactory<String, byte[]> manifestEventConsumerFactory(SbpRouterProperties properties) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "sbp-router");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new ByteArrayDeserializer());
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, byte[]> manifestEventListenerContainerFactory(
            ConsumerFactory<String, byte[]> manifestEventConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(manifestEventConsumerFactory);
        return factory;
    }
}
