package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.assertThat;

class AdminTokenFilterTest {
    private final AdminTokenFilter filter = new AdminTokenFilter("secret");
    private final WebFilterChain passthrough = exchange -> Mono.empty();

    @Test void allowsNonAdminPathWithoutToken() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/gcsvc"));
        filter.filter(ex, passthrough).block();
        assertThat(ex.getResponse().getStatusCode()).isNull();
    }
    @Test void rejectsAdminPathWithoutToken() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/admin/config"));
        filter.filter(ex, passthrough).block();
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void rejectsAdminPathWithWrongToken() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/admin/config").header(HttpHeaders.AUTHORIZATION, "Bearer nope"));
        filter.filter(ex, passthrough).block();
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void allowsAdminPathWithCorrectToken() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/admin/config").header(HttpHeaders.AUTHORIZATION, "Bearer secret"));
        filter.filter(ex, passthrough).block();
        assertThat(ex.getResponse().getStatusCode()).isNull();
    }
}
