package ru.copperside.sbprouter.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import ru.copperside.sbprouter.balancing.AllBackendsFailedException;
import ru.copperside.sbprouter.balancing.Backend;
import ru.copperside.sbprouter.balancing.BackendGroup;
import ru.copperside.sbprouter.balancing.BackendGroupRegistry;
import ru.copperside.sbprouter.balancing.LoadBalancer;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@Component
public class ProxyClient {
    private static final Logger log = LoggerFactory.getLogger(ProxyClient.class);

    private static final Set<String> HOP_BY_HOP = Set.of(
            "host", "connection", "content-length", "transfer-encoding", "keep-alive",
            "te", "trailer", "upgrade", "proxy-authenticate", "proxy-authorization", "expect");

    private static final Set<String> STRIPPED_REQUEST_HEADERS = Set.of(
            "authorization", "cookie", "x-api-key");

    private final WebClient webClient;
    private final SbpRouterProperties properties;
    private final BackendGroupRegistry registry;
    private final LoadBalancer loadBalancer;
    private final Clock clock;

    public ProxyClient(WebClient proxyWebClient, SbpRouterProperties properties,
                       BackendGroupRegistry registry, LoadBalancer loadBalancer, Clock clock) {
        this.webClient = proxyWebClient;
        this.properties = properties;
        this.registry = registry;
        this.loadBalancer = loadBalancer;
        this.clock = clock;
    }

    /**
     * Forwards the request to the active group: round-robin across healthy backends, failing over to
     * the next on a transport error, up to {@code failover.max-attempts} distinct backends. The first
     * backend that returns any HTTP response wins and its status/headers/body are relayed verbatim.
     */
    public Mono<ProxyResult> forward(byte[] body, HttpHeaders requestHeaders) {
        BackendGroup group = registry.activeGroup();
        List<Backend> candidates = loadBalancer.selectCandidates(group);
        int k = Math.max(1, properties.getFailover().getMaxAttempts());
        int limit = Math.min(k, candidates.size());
        Duration timeout = properties.getTimeout() != null ? properties.getTimeout() : Duration.ofSeconds(30);
        int threshold = properties.getCircuitBreaker().getFailureThreshold();
        long banMs = properties.getCircuitBreaker().getBanDuration().toMillis();
        return attempt(candidates, 0, limit, body, requestHeaders, timeout, threshold, banMs, false);
    }

    private Mono<ProxyResult> attempt(List<Backend> candidates, int idx, int limit, byte[] body,
                                      HttpHeaders requestHeaders, Duration timeout, int threshold,
                                      long banMs, boolean lastWasTimeout) {
        if (idx >= limit) {
            return Mono.error(new AllBackendsFailedException(lastWasTimeout));
        }
        Backend backend = candidates.get(idx);
        return webClient.post().uri(backend.url())
                .headers(h -> copyForwardable(requestHeaders, h))
                .bodyValue(body)
                .exchangeToMono(response -> response.toEntity(byte[].class))
                .timeout(timeout)
                .map(entity -> {
                    backend.health().recordSuccess();
                    return new ProxyResult(
                            entity.getStatusCode(),
                            stripHopByHop(entity.getHeaders()),
                            entity.getBody() != null ? entity.getBody() : new byte[0]);
                })
                .onErrorResume(ex -> {
                    boolean timedOut = ex instanceof TimeoutException;
                    backend.health().recordFailure(clock.millis(), threshold, banMs);
                    log.warn("Backend {} failed ({}); failing over (attempt {} of {})",
                            backend.url(), ex.toString(), idx + 1, limit);
                    return attempt(candidates, idx + 1, limit, body, requestHeaders, timeout,
                            threshold, banMs, timedOut);
                });
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
