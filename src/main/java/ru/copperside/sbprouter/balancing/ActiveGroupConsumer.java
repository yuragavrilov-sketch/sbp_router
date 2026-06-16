package ru.copperside.sbprouter.balancing;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG;

/**
 * Applies active-group switches broadcast on the compacted active-group topic. Each pod uses a
 * UNIQUE consumer group (keyed by instanceId) so every replica receives every switch and converges
 * to the same active group. On startup the pod replays the topic from earliest, so it picks up the
 * latest active group even if the switch happened while it was down.
 */
@Component
@ConditionalOnProperty(prefix = "sbp-router.active-group-sync", name = "enabled", havingValue = "true")
public class ActiveGroupConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ActiveGroupConsumer.class);

    private final BackendGroupRegistry registry;
    private final String topic;
    private final Map<String, Object> consumerConfig;

    private volatile boolean running = false;
    private volatile KafkaConsumer<String, byte[]> consumer;
    private Thread thread;

    public ActiveGroupConsumer(BackendGroupRegistry registry, SbpRouterProperties props,
                               @Qualifier("instanceId") String instanceId) {
        this.registry = registry;
        this.topic = props.getActiveGroupSync().getTopic();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(BOOTSTRAP_SERVERS_CONFIG, props.getKafka().getBootstrapServers());
        cfg.put(GROUP_ID_CONFIG, "sbp-router-active-group-" + instanceId); // unique per pod
        cfg.put(KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        cfg.put(AUTO_OFFSET_RESET_CONFIG, "earliest");
        // No offset commits: each pod always replays from earliest and applies the latest record,
        // so committing buys nothing and would leave an orphaned consumer group (unique per restart)
        // accumulating in __consumer_offsets.
        cfg.put(ENABLE_AUTO_COMMIT_CONFIG, false);
        this.consumerConfig = cfg;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        consumer = new KafkaConsumer<>(consumerConfig);
        thread = new Thread(this::pollLoop, "active-group-consumer");
        thread.setDaemon(true);
        thread.start();
        log.info("Active-group consumer started on topic '{}'", topic);
    }

    private void pollLoop() {
        try {
            consumer.subscribe(List.of(topic));
            while (running) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, byte[]> record : records) {
                    apply(record);
                }
            }
        } catch (WakeupException e) {
            if (running) {
                throw e; // unexpected wakeup
            }
        } catch (Exception e) {
            log.error("Active-group consumer loop failed: {}", e.toString(), e);
        } finally {
            consumer.close();
        }
    }

    private void apply(ConsumerRecord<String, byte[]> record) {
        String name = record.value() == null ? null : new String(record.value(), StandardCharsets.UTF_8);
        if (name != null && registry.groups().containsKey(name)) {
            registry.setActiveGroup(name);
            log.info("Active group switched to '{}' via Kafka", name);
        } else {
            log.warn("Ignoring unknown group '{}' from active-group topic", name);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (consumer != null) {
            consumer.wakeup();
        }
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
