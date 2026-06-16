package ru.copperside.sbprouter.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.copperside.sbprouter.balancing.Backend;
import ru.copperside.sbprouter.balancing.BackendGroup;
import ru.copperside.sbprouter.balancing.BackendGroupRegistry;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.MAX_BLOCK_MS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG;

/**
 * Publishes a periodic fleet heartbeat (this pod's presence + key metrics) to a Kafka topic so the
 * management service can show how many routers are running and their state. Fire-and-forget — a
 * failed heartbeat never disturbs proxying. Scheduling uses a dedicated daemon executor (no Spring
 * @Scheduled / SpEL-on-Duration).
 */
@Component
@ConditionalOnProperty(prefix = "sbp-router.heartbeat", name = "enabled", havingValue = "true")
public class HeartbeatPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatPublisher.class);

    private final BackendGroupRegistry registry;
    private final MetricsService metrics;
    private final SbpRouterProperties props;
    private final String instanceId;
    private final Instant startedAt;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final KafkaProducer<String, byte[]> producer;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "heartbeat");
        t.setDaemon(true);
        return t;
    });

    public HeartbeatPublisher(BackendGroupRegistry registry, MetricsService metrics,
                              SbpRouterProperties props, @Qualifier("instanceId") String instanceId,
                              @Qualifier("startedAt") Instant startedAt, Clock clock) {
        this.registry = registry;
        this.metrics = metrics;
        this.props = props;
        this.instanceId = instanceId;
        this.startedAt = startedAt;
        this.clock = clock;
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(BOOTSTRAP_SERVERS_CONFIG, props.getKafka().getBootstrapServers());
        cfg.put(KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        cfg.put(ACKS_CONFIG, "0"); // presence ping — delivery guarantees not needed
        cfg.put(MAX_BLOCK_MS_CONFIG, "5000");
        this.producer = new KafkaProducer<>(cfg);
    }

    @PostConstruct
    void schedule() {
        long ms = Math.max(1000L, props.getHeartbeat().getInterval().toMillis());
        scheduler.scheduleWithFixedDelay(this::beat, ms, ms, TimeUnit.MILLISECONDS);
        log.info("Heartbeat publisher scheduled every {}ms to topic '{}'", ms, props.getHeartbeat().getTopic());
    }

    void beat() {
        try {
            byte[] json = mapper.writeValueAsBytes(buildPayload());
            producer.send(new ProducerRecord<>(props.getHeartbeat().getTopic(), instanceId, json));
        } catch (Exception e) {
            log.warn("heartbeat publish failed: {}", e.toString());
        }
    }

    /** Package-private for unit testing the payload shape without Kafka. */
    Map<String, Object> buildPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", instanceId);
        payload.put("startedAt", startedAt.toString());
        payload.put("timestamp", clock.instant().toString());
        payload.put("activeGroup", registry.activeGroupName());
        payload.put("groups", new ArrayList<>(registry.groups().keySet()));

        long now = clock.millis();
        List<Map<String, Object>> backends = new ArrayList<>();
        for (BackendGroup group : registry.groups().values()) {
            for (Backend backend : group.backends()) {
                Map<String, Object> bm = new LinkedHashMap<>();
                bm.put("url", backend.url());
                bm.put("group", group.name());
                bm.put("banned", !backend.health().available(now));
                backends.add(bm);
            }
        }
        payload.put("backends", backends);

        MetricsService.MetricsSnapshot s = metrics.snapshot();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activeRequests", s.activeRequests());
        m.put("requestsTotal", s.requestsTotal());
        m.put("upstreamErrorsTotal", s.upstreamErrorsTotal());
        m.put("kafkaPublishedTotal", s.kafkaPublishedTotal());
        m.put("requestCount", s.requestCount());
        m.put("avgLatencyMs", s.avgLatencyMs());
        m.put("maxLatencyMs", s.maxLatencyMs());
        payload.put("metrics", m);
        return payload;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        producer.close(java.time.Duration.ofSeconds(1));
    }
}
