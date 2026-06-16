package ru.copperside.sbprouter.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SbpRouterPropertiesTest {

    @Test
    void defaults() {
        SbpRouterProperties p = new SbpRouterProperties();
        assertThat(p.getActiveGroup()).isEqualTo("default");
        assertThat(p.getGroups()).isEmpty();
        assertThat(p.getTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.getFailover().getMaxAttempts()).isEqualTo(2);
        assertThat(p.getCircuitBreaker().getFailureThreshold()).isEqualTo(3);
        assertThat(p.getCircuitBreaker().getBanDuration()).isEqualTo(Duration.ofSeconds(30));
        // Kafka defaults preserved
        assertThat(p.getKafka().isEnabled()).isFalse();
        assertThat(p.getKafka().getTopic()).isEqualTo("sbp-router-traffic");
        // ActiveGroupSync defaults
        assertThat(p.getActiveGroupSync().isEnabled()).isFalse();
        assertThat(p.getActiveGroupSync().getTopic()).isEqualTo("sbp-router-active-group");
        // Heartbeat defaults
        assertThat(p.getHeartbeat().isEnabled()).isFalse();
        assertThat(p.getHeartbeat().getTopic()).isEqualTo("sbp-router-heartbeat");
        assertThat(p.getHeartbeat().getInterval()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void groupAccessors() {
        SbpRouterProperties.Group g = new SbpRouterProperties.Group();
        g.setBackends(java.util.List.of("http://a/api", "http://b/api"));
        SbpRouterProperties p = new SbpRouterProperties();
        p.setActiveGroup("primary");
        p.getGroups().put("primary", g);
        assertThat(p.getActiveGroup()).isEqualTo("primary");
        assertThat(p.getGroups().get("primary").getBackends()).containsExactly("http://a/api", "http://b/api");
    }
}
