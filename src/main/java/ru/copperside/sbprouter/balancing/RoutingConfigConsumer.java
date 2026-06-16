package ru.copperside.sbprouter.balancing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG;

/**
 * Consumes routing config published by sbp-router-management on the compacted topic
 * {@code sbp-router-routing-config} and applies it to the {@link BackendGroupRegistry}.
 *
 * <p>Each pod uses a unique consumer group (keyed by instanceId) so every replica receives every
 * published config and converges. On startup the pod replays from earliest, applying the latest
 * config even if it was published while the pod was down. Version deduplication ensures that
 * an older config replayed after a newer one (e.g. due to out-of-order replay) is ignored.
 */
@Component
@ConditionalOnProperty(prefix = "sbp-router.routing-config", name = "enabled", havingValue = "true")
public class RoutingConfigConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RoutingConfigConsumer.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final BackendGroupRegistry registry;
    private final String topic;
    private final Map<String, Object> consumerConfig;
    private volatile boolean running = false;
    private volatile KafkaConsumer<String, byte[]> consumer;
    private Thread thread;

    public RoutingConfigConsumer(BackendGroupRegistry registry, SbpRouterProperties props,
                                 @Qualifier("instanceId") String instanceId) {
        this.registry = registry;
        this.topic = props.getRoutingConfig().getTopic();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(BOOTSTRAP_SERVERS_CONFIG, props.getKafka().getBootstrapServers());
        cfg.put(GROUP_ID_CONFIG, "sbp-router-routing-config-" + instanceId);
        cfg.put(KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        cfg.put(AUTO_OFFSET_RESET_CONFIG, "earliest");
        cfg.put(ENABLE_AUTO_COMMIT_CONFIG, false);
        this.consumerConfig = cfg;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        consumer = new KafkaConsumer<>(consumerConfig);
        thread = new Thread(this::pollLoop, "routing-config-consumer");
        thread.setDaemon(true);
        thread.start();
        log.info("Routing-config consumer started on topic '{}'", topic);
    }

    private void pollLoop() {
        try {
            consumer.subscribe(List.of(topic));
            while (running) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, byte[]> r : records) apply(r);
            }
        } catch (WakeupException e) {
            if (running) throw e;
        } catch (Exception e) {
            log.error("routing-config consumer loop failed: {}", e.toString(), e);
        } finally {
            consumer.close();
        }
    }

    private void apply(ConsumerRecord<String, byte[]> record) {
        if (record.value() == null) return;
        try {
            JsonNode n = mapper.readTree(record.value());
            long version = n.path("version").asLong(0);
            if (version <= registry.appliedVersion()) return;
            String active = n.path("activeGroup").asText(null);
            Map<String, BackendGroup> groups = new LinkedHashMap<>();
            JsonNode gs = n.path("groups");
            gs.fieldNames().forEachRemaining(name -> {
                List<Backend> backends = new ArrayList<>();
                for (JsonNode url : gs.path(name).path("backends")) {
                    backends.add(new Backend(url.asText(), new BackendHealth()));
                }
                groups.put(name, new BackendGroup(name, backends));
            });
            registry.replace(groups, active, version);
            log.info("Applied routing config v{}: activeGroup={} groups={}", version, active, groups.keySet());
        } catch (Exception e) {
            log.warn("routing-config: skipping unparseable/invalid message: {}", e.toString());
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (consumer != null) consumer.wakeup();
        if (thread != null) try { thread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override
    public boolean isRunning() { return running; }
}
