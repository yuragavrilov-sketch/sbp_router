package ru.copperside.sbprouter.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import reactor.kafka.sender.KafkaSender;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Configuration
    @EnableConfigurationProperties(SbpRouterProperties.class)
    @Import(KafkaConfig.class)
    static class TestConfig {
    }

    @Test
    void senderBeanPresentWhenEnabled() {
        runner.withPropertyValues("sbp-router.kafka.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(KafkaSender.class));
    }

    @Test
    void senderBeanAbsentWhenDisabled() {
        runner.withPropertyValues("sbp-router.kafka.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(KafkaSender.class));
    }

    @Test
    void senderBeanAbsentByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(KafkaSender.class));
    }
}
