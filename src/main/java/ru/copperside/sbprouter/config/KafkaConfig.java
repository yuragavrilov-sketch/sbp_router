package ru.copperside.sbprouter.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "sbp-router.kafka", name = "enabled", havingValue = "true")
    public KafkaSender<String, byte[]> trafficKafkaSender(SbpRouterProperties properties) {
        SbpRouterProperties.Kafka kafka = properties.getKafka();
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        // Bounded so an unavailable broker fails fast instead of blocking the reactive event loop or accumulating buffers.
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 16L * 1024 * 1024);
        SenderOptions<String, byte[]> senderOptions = SenderOptions.create(props);
        return KafkaSender.create(senderOptions);
    }
}
