package ru.copperside.sbprouter.balancing;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.apache.kafka.clients.producer.ProducerConfig.*;

@Component
@ConditionalOnProperty(prefix = "sbp-router.active-group-sync", name = "enabled", havingValue = "true")
public class ActiveGroupPublisher implements AutoCloseable {

    private final KafkaProducer<String, byte[]> producer;
    private final String topic;

    public ActiveGroupPublisher(SbpRouterProperties props) {
        this.topic = props.getActiveGroupSync().getTopic();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(BOOTSTRAP_SERVERS_CONFIG, props.getKafka().getBootstrapServers());
        cfg.put(KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        cfg.put(ACKS_CONFIG, "1");
        cfg.put(MAX_BLOCK_MS_CONFIG, "5000");
        this.producer = new KafkaProducer<>(cfg);
    }

    public void publish(String groupName) {
        try {
            producer.send(new ProducerRecord<>(topic, "active-group",
                    groupName.getBytes(StandardCharsets.UTF_8))).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("active-group publish failed", e);
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
