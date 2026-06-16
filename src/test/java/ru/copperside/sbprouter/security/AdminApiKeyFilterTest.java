package ru.copperside.sbprouter.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AdminApiKeyFilterTest {

    private static AdminApiKeyFilter filter(String key) {
        SbpRouterProperties p = new SbpRouterProperties();
        p.getAdmin().setApiKey(key);
        return new AdminApiKeyFilter(p);
    }

    private static WebFilterChain chain(AtomicBoolean passed) {
        return exchange -> {
            passed.set(true);
            return Mono.empty();
        };
    }

    @Test
    void blankKeyDisablesProtection() {
        AtomicBoolean passed = new AtomicBoolean(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/actuator/activegroup").build());

        filter("").filter(exchange, chain(passed)).block();

        assertThat(passed).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void protectedPathWithoutKeyIsUnauthorized() {
        AtomicBoolean passed = new AtomicBoolean(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/actuator/activegroup").build());

        filter("secret").filter(exchange, chain(passed)).block();

        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedPathWithWrongKeyIsUnauthorized() {
        AtomicBoolean passed = new AtomicBoolean(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/actuator/activegroup")
                        .header("X-Internal-Admin-Key", "nope").build());

        filter("secret").filter(exchange, chain(passed)).block();

        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedPathWithCorrectKeyPasses() {
        AtomicBoolean passed = new AtomicBoolean(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/activegroup")
                        .header("X-Internal-Admin-Key", "secret").build());

        filter("secret").filter(exchange, chain(passed)).block();

        assertThat(passed).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void nonAdminPathAlwaysPassesEvenWhenKeySet() {
        AtomicBoolean passed = new AtomicBoolean(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/gcsvc").build());

        filter("secret").filter(exchange, chain(passed)).block();

        assertThat(passed).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void healthEndpointStaysOpen() {
        AtomicBoolean passed = new AtomicBoolean(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build());

        filter("secret").filter(exchange, chain(passed)).block();

        assertThat(passed).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}
