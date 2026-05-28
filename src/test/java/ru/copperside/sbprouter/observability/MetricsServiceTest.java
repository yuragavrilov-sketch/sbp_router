package ru.copperside.sbprouter.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsServiceTest {

    @Test
    void recordsKafkaPublishedAndErrorCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsService metrics = new MetricsService(registry);

        metrics.recordKafkaPublished("request");
        metrics.recordKafkaPublished("response");
        metrics.recordKafkaPublished("response");
        metrics.recordKafkaPublishError("request");

        assertThat(registry.get("sbp_router_kafka_published_total")
                .tag("direction", "request").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("sbp_router_kafka_published_total")
                .tag("direction", "response").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("sbp_router_kafka_publish_errors_total")
                .tag("direction", "request").counter().count()).isEqualTo(1.0);
    }
}
