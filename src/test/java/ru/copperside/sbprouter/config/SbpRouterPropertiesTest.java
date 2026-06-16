package ru.copperside.sbprouter.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SbpRouterPropertiesTest {

    @Autowired
    private SbpRouterProperties props;

    @Test
    void backendConfigLoaded() {
        assertThat(props.getBackend()).isNotNull();
        assertThat(props.getBackend().getUrl()).isNotBlank();
        assertThat(props.getBackend().getTimeout()).isNotNull();
        assertThat(props.getBackend().getRetry()).isNotNull();
        assertThat(props.getBackend().getRetry().getMaxAttempts()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void backendDefaultsAndAccessors() {
        SbpRouterProperties.Backend backend = new SbpRouterProperties.Backend();

        assertThat(backend.getTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(backend.getRetry().getMaxAttempts()).isEqualTo(2);
        assertThat(backend.getRetry().getBackoff()).isEqualTo(Duration.ofMillis(500));

        backend.setUrl("http://backend.local/api/gcsvc");
        backend.setTimeout(Duration.ofSeconds(10));
        assertThat(backend.getUrl()).isEqualTo("http://backend.local/api/gcsvc");
        assertThat(backend.getTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void kafkaDefaultsAndAccessors() {
        SbpRouterProperties props = new SbpRouterProperties();

        // defaults
        assertThat(props.getKafka().isEnabled()).isFalse();
        assertThat(props.getKafka().getBootstrapServers()).isEqualTo("localhost:9092");
        assertThat(props.getKafka().getTopic()).isEqualTo("sbp-router-traffic");

        // setters
        props.getKafka().setEnabled(true);
        props.getKafka().setBootstrapServers("kafka:9092");
        props.getKafka().setTopic("custom-topic");
        assertThat(props.getKafka().isEnabled()).isTrue();
        assertThat(props.getKafka().getBootstrapServers()).isEqualTo("kafka:9092");
        assertThat(props.getKafka().getTopic()).isEqualTo("custom-topic");
    }
}
