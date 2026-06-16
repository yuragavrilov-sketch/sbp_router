package ru.copperside.sbprouter.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import ru.copperside.sbprouter.balancing.BackendGroupRegistry;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatPublisherTest {

    @SuppressWarnings("unchecked")
    @Test
    void payloadContainsIdentityGroupsBackendsAndMetrics() {
        SbpRouterProperties p = new SbpRouterProperties();
        p.setActiveGroup("default");
        SbpRouterProperties.Group d = new SbpRouterProperties.Group();
        d.setBackends(List.of("http://a/api/gcsvc", "http://b/api/gcsvc"));
        p.getGroups().put("default", d);
        p.getKafka().setBootstrapServers("localhost:9092"); // not contacted in this test

        BackendGroupRegistry registry = new BackendGroupRegistry(p);
        MetricsService metrics = new MetricsService(new SimpleMeterRegistry());
        metrics.recordRequest();

        HeartbeatPublisher hb = new HeartbeatPublisher(
                registry, metrics, p, "inst-1", Instant.parse("2026-06-16T10:00:00Z"), Clock.systemUTC());
        try {
            Map<String, Object> payload = hb.buildPayload();
            assertThat(payload.get("instanceId")).isEqualTo("inst-1");
            assertThat(payload.get("startedAt")).isEqualTo("2026-06-16T10:00:00Z");
            assertThat(payload.get("activeGroup")).isEqualTo("default");
            assertThat((List<String>) payload.get("groups")).containsExactly("default");
            assertThat((List<Map<String, Object>>) payload.get("backends")).hasSize(2);
            Map<String, Object> backend = ((List<Map<String, Object>>) payload.get("backends")).get(0);
            assertThat(backend).containsKeys("url", "group", "banned");
            assertThat(backend.get("group")).isEqualTo("default");
            Map<String, Object> m = (Map<String, Object>) payload.get("metrics");
            assertThat(m).containsKeys("activeRequests", "requestsTotal", "avgLatencyMs");
            assertThat(((Number) m.get("requestsTotal")).doubleValue()).isEqualTo(1.0);
        } finally {
            hb.close();
        }
    }
}
