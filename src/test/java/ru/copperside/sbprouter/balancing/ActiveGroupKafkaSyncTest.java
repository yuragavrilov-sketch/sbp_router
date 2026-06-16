package ru.copperside.sbprouter.balancing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the cross-replica guarantee: a switch published once is applied by EVERY replica. Two
 * independent registries + consumers (distinct instanceId = distinct consumer group) both converge
 * to the broadcast active group.
 */
@ExtendWith(SpringExtension.class)
@EmbeddedKafka(partitions = 1, topics = ActiveGroupKafkaSyncTest.TOPIC)
class ActiveGroupKafkaSyncTest {

    static final String TOPIC = "sbp-router-active-group";

    private static SbpRouterProperties props(String brokers) {
        SbpRouterProperties p = new SbpRouterProperties();
        p.setActiveGroup("default");
        SbpRouterProperties.Group d = new SbpRouterProperties.Group();
        d.setBackends(List.of("http://a/api/gcsvc"));
        SbpRouterProperties.Group s = new SbpRouterProperties.Group();
        s.setBackends(List.of("http://b/api/gcsvc"));
        p.getGroups().put("default", d);
        p.getGroups().put("secondary", s);
        p.getKafka().setBootstrapServers(brokers);
        p.getActiveGroupSync().setEnabled(true);
        p.getActiveGroupSync().setTopic(TOPIC);
        return p;
    }

    @Test
    void allReplicasConvergeOnPublishedSwitch() throws InterruptedException {
        String brokers = System.getProperty("spring.embedded.kafka.brokers");

        SbpRouterProperties p1 = props(brokers);
        SbpRouterProperties p2 = props(brokers);
        BackendGroupRegistry r1 = new BackendGroupRegistry(p1);
        BackendGroupRegistry r2 = new BackendGroupRegistry(p2);
        ActiveGroupConsumer c1 = new ActiveGroupConsumer(r1, p1, "pod-1");
        ActiveGroupConsumer c2 = new ActiveGroupConsumer(r2, p2, "pod-2");
        ActiveGroupPublisher publisher = new ActiveGroupPublisher(p1);

        try {
            c1.start();
            c2.start();
            // Both start on the configured default.
            assertThat(r1.activeGroupName()).isEqualTo("default");
            assertThat(r2.activeGroupName()).isEqualTo("default");

            publisher.publish("secondary");

            long deadline = System.currentTimeMillis() + 15_000;
            while ((!"secondary".equals(r1.activeGroupName()) || !"secondary".equals(r2.activeGroupName()))
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }

            assertThat(r1.activeGroupName()).isEqualTo("secondary");
            assertThat(r2.activeGroupName()).isEqualTo("secondary");

            // A pod that joins AFTER the switch still converges (replay from earliest).
            SbpRouterProperties p3 = props(brokers);
            BackendGroupRegistry r3 = new BackendGroupRegistry(p3);
            ActiveGroupConsumer c3 = new ActiveGroupConsumer(r3, p3, "pod-3");
            try {
                c3.start();
                long d3 = System.currentTimeMillis() + 15_000;
                while (!"secondary".equals(r3.activeGroupName()) && System.currentTimeMillis() < d3) {
                    Thread.sleep(200);
                }
                assertThat(r3.activeGroupName()).isEqualTo("secondary");
            } finally {
                c3.stop();
            }
        } finally {
            publisher.close();
            c1.stop();
            c2.stop();
        }
    }
}
