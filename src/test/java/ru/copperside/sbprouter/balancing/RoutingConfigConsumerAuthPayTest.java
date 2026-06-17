package ru.copperside.sbprouter.balancing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingConfigConsumerAuthPayTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesEnabledAuthPay() throws Exception {
        AuthPayRoute r = RoutingConfigConsumer.parseAuthPay(mapper.readTree(
                "{\"authPay\":{\"enabled\":true,\"backends\":[\"http://authpay/x\"],\"timeoutMs\":1500}}"));
        assertThat(r.enabled()).isTrue();
        assertThat(r.pool().backends()).hasSize(1);
        assertThat(r.timeout()).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    void disabledWhenAbsentEmptyOrFlagOff() throws Exception {
        assertThat(RoutingConfigConsumer.parseAuthPay(mapper.readTree("{}")).enabled()).isFalse();
        assertThat(RoutingConfigConsumer.parseAuthPay(mapper.readTree(
                "{\"authPay\":{\"enabled\":true,\"backends\":[]}}")).enabled()).isFalse();
        assertThat(RoutingConfigConsumer.parseAuthPay(mapper.readTree(
                "{\"authPay\":{\"enabled\":false,\"backends\":[\"http://x\"]}}")).enabled()).isFalse();
    }
}
