package ru.copperside.sbprouter.security;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards the admin API — the {@code /admin/active-group} endpoint, which can redirect all proxied
 * traffic at runtime — with a shared key. The GCSvc proxy port also serves admin paths, so without
 * this the switch endpoint would be reachable by anyone who can reach the proxy ingress.
 *
 * <p>When {@code sbp-router.admin.api-key} is blank the guard is disabled (local/dev). When set,
 * requests to {@code /admin/**} must carry the key in the configured header, else 401.
 * Observability endpoints (health/info/metrics/prometheus) are intentionally left open so k8s probes
 * and scraping keep working.
 */
@Component
public class AdminApiKeyFilter implements WebFilter, Ordered {

    private static final String PROTECTED_PREFIX = "/admin";

    private final String apiKey;
    private final String headerName;

    public AdminApiKeyFilter(SbpRouterProperties properties) {
        this.apiKey = properties.getAdmin().getApiKey();
        this.headerName = properties.getAdmin().getHeaderName();
    }

    @Override
    public int getOrder() {
        // Run before request-body logging so an unauthorized admin call is rejected early.
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (apiKey == null || apiKey.isBlank()) {
            return chain.filter(exchange); // protection disabled
        }
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith(PROTECTED_PREFIX)) {
            return chain.filter(exchange);
        }
        String provided = exchange.getRequest().getHeaders().getFirst(headerName);
        if (matches(provided)) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    /** Constant-time comparison to avoid leaking the key length / prefix via timing. */
    private boolean matches(String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
