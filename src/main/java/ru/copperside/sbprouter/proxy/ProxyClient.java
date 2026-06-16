package ru.copperside.sbprouter.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

@Component
public class ProxyClient {
    private static final Logger log = LoggerFactory.getLogger(ProxyClient.class);

    // Hop-by-hop headers a proxy must not forward (RFC 7230 §6.1), plus Host/Content-Length which the
    // client connector derives from the target URI / body. Applied to both forwarded request headers
    // and relayed response headers so the proxy stays transparent without corrupting framing.
    private static final Set<String> HOP_BY_HOP = Set.of(
            "host", "connection", "content-length", "transfer-encoding", "keep-alive",
            "te", "trailer", "upgrade", "proxy-authenticate", "proxy-authorization", "expect");

    // Caller credentials are NOT forwarded to the backend: the router authenticates to the receiver on
    // its own terms, and relaying a client's Authorization/Cookie would be a confused-deputy risk.
    private static final Set<String> STRIPPED_REQUEST_HEADERS = Set.of(
            "authorization", "cookie", "x-api-key");

    private final WebClient webClient;
    private final SbpRouterProperties properties;

    public ProxyClient(WebClient proxyWebClient, SbpRouterProperties properties) {
        this.webClient = proxyWebClient;
        this.properties = properties;
    }

    /**
     * Forwards the request body to the single configured backend and relays its status, headers and
     * body verbatim (transparent L7 pass-through). The router does not rewrite payload, status or
     * headers. Original request headers are forwarded (minus hop-by-hop and caller credentials).
     */
    public Mono<ProxyResult> forward(byte[] body, HttpHeaders requestHeaders) {
        SbpRouterProperties.Backend backend = properties.getBackend();
        if (backend == null || backend.getUrl() == null || backend.getUrl().isBlank()) {
            return Mono.error(new IllegalStateException("Backend URL is not configured (sbp-router.backend.url)"));
        }
        Duration timeout = backend.getTimeout() != null ? backend.getTimeout() : Duration.ofSeconds(30);
        int maxAttempts = backend.getRetry() != null ? backend.getRetry().getMaxAttempts() : 1;
        Duration backoff = backend.getRetry() != null ? backend.getRetry().getBackoff() : Duration.ofMillis(500);

        log.info("Forwarding to backend url={} bodySize={} timeout={}s",
                backend.getUrl(), body.length, timeout.getSeconds());

        return webClient.post().uri(backend.getUrl())
                .headers(h -> copyForwardable(requestHeaders, h))
                .bodyValue(body)
                // exchangeToMono (not retrieve) so 4xx/5xx are relayed verbatim, not thrown.
                .exchangeToMono(response -> response.toEntity(byte[].class))
                .map(entity -> new ProxyResult(
                        entity.getStatusCode(),
                        stripHopByHop(entity.getHeaders()),
                        entity.getBody() != null ? entity.getBody() : new byte[0]))
                .timeout(timeout)
                // Retry only transport failures; an HTTP error status is a valid relayed response.
                .retryWhen(Retry.backoff(maxAttempts, backoff)
                        .filter(ex -> !(ex instanceof java.util.concurrent.TimeoutException))
                        .doBeforeRetry(signal -> log.warn("Retrying backend attempt={}", signal.totalRetries() + 1)))
                .doOnError(ex -> log.error("Backend forward failed: {}", ex.getMessage()));
    }

    private static void copyForwardable(HttpHeaders src, HttpHeaders dst) {
        if (src == null) {
            return;
        }
        src.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP.contains(lower) && !STRIPPED_REQUEST_HEADERS.contains(lower)) {
                dst.addAll(name, values);
            }
        });
    }

    private static HttpHeaders stripHopByHop(HttpHeaders src) {
        HttpHeaders out = new HttpHeaders();
        src.forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                out.addAll(name, values);
            }
        });
        return out;
    }

    /** Backend response relayed back to the caller verbatim. */
    public record ProxyResult(HttpStatusCode status, HttpHeaders headers, byte[] body) {
    }
}
