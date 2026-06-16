package ru.copperside.sbprouter.balancing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.apache.kafka.clients.producer.ProducerConfig.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the cross-replica guarantee: a routing config published once is applied by EVERY replica.
 * Two independent registries + consumers (distinct instanceId = distinct consumer group) both
 * converge to the broadcast config. Also verifies version deduplication (older version ignored).
 */
@ExtendWith(SpringExtension.class)
@EmbeddedKafka(partitions = 1, topics = RoutingConfigKafkaSyncTest.TOPIC)
class RoutingConfigKafkaSyncTest {

    static final String TOPIC = "sbp-router-routing-config";

    private static SbpRouterProperties props(String brokers) {
        SbpRouterProperties p = new SbpRouterProperties();
        p.setActiveGroup("default");
        SbpRouterProperties.Group d = new SbpRouterProperties.Group();
        d.setBackends(List.of("http://a/api/gcsvc"));
        p.getGroups().put("default", d);
        p.getKafka().setBootstrapServers(brokers);
        p.getRoutingConfig().setEnabled(true);
        p.getRoutingConfig().setTopic(TOPIC);
        return p;
    }

    private static KafkaProducer<String, byte[]> producer(String brokers) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(BOOTSTRAP_SERVERS_CONFIG, brokers);
        cfg.put(KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        cfg.put(ACKS_CONFIG, "1");
        return new KafkaProducer<>(cfg);
    }

    private static byte[] configJson(long version, String activeGroup, String groupName, String backendUrl)
            throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("version", version);
        payload.put("activeGroup", activeGroup);
        payload.put("groups", Map.of(groupName, Map.of("backends", List.of(backendUrl))));
        return new ObjectMapper().writeValueAsBytes(payload);
    }

    @Test
    void allReplicasConvergeOnPublishedConfig() throws Exception {
        String brokers = System.getProperty("spring.embedded.kafka.brokers");

        SbpRouterProperties p1 = props(brokers);
        SbpRouterProperties p2 = props(brokers);
        BackendGroupRegistry r1 = new BackendGroupRegistry(p1);
        BackendGroupRegistry r2 = new BackendGroupRegistry(p2);
        RoutingConfigConsumer c1 = new RoutingConfigConsumer(r1, p1, "pod-1");
        RoutingConfigConsumer c2 = new RoutingConfigConsumer(r2, p2, "pod-2");

        KafkaProducer<String, byte[]> pub = producer(brokers);
        try {
            c1.start();
            c2.start();

            // Both start on the default bootstrap config, version 0.
            assertThat(r1.activeGroupName()).isEqualTo("default");
            assertThat(r2.activeGroupName()).isEqualTo("default");
            assertThat(r1.appliedVersion()).isZero();

            // Publish version 2 (dr group).
            byte[] v2 = configJson(2L, "dr", "dr", "http://b/api/gcsvc");
            pub.send(new ProducerRecord<>(TOPIC, "routing-config", v2)).get(5, TimeUnit.SECONDS);

            // Both pods must converge.
            long deadline = System.currentTimeMillis() + 15_000;
            while ((r1.appliedVersion() < 2 || r2.appliedVersion() < 2)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }

            assertThat(r1.appliedVersion()).isEqualTo(2L);
            assertThat(r1.activeGroupName()).isEqualTo("dr");
            assertThat(r2.appliedVersion()).isEqualTo(2L);
            assertThat(r2.activeGroupName()).isEqualTo("dr");

            // Publish version 1 AFTER version 2 — must be ignored (version dedup).
            byte[] v1 = configJson(1L, "default", "default", "http://a/api/gcsvc");
            pub.send(new ProducerRecord<>(TOPIC, "routing-config", v1)).get(5, TimeUnit.SECONDS);
            Thread.sleep(1000); // give consumers time to process
            assertThat(r1.appliedVersion()).isEqualTo(2L); // still 2, not reverted to 1
            assertThat(r1.activeGroupName()).isEqualTo("dr");

            // A pod that joins AFTER the switch still converges (replay from earliest).
            SbpRouterProperties p3 = props(brokers);
            BackendGroupRegistry r3 = new BackendGroupRegistry(p3);
            RoutingConfigConsumer c3 = new RoutingConfigConsumer(r3, p3, "pod-3");
            try {
                c3.start();
                long d3 = System.currentTimeMillis() + 15_000;
                while (r3.appliedVersion() < 2 && System.currentTimeMillis() < d3) {
                    Thread.sleep(200);
                }
                assertThat(r3.appliedVersion()).isEqualTo(2L);
                assertThat(r3.activeGroupName()).isEqualTo("dr");
            } finally {
                c3.stop();
            }

        } finally {
            pub.close();
            c1.stop();
            c2.stop();
        }
    }
}
