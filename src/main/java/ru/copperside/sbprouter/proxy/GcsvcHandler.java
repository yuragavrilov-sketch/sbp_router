package ru.copperside.sbprouter.proxy;

import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import ru.copperside.sbprouter.extraction.CorrelationIdExtractor;
import ru.copperside.sbprouter.observability.MetricsService;
import ru.copperside.sbprouter.observability.TrafficPublisher;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Flat GCSvc proxy: accept a request, publish it to Kafka, forward it verbatim to the single
 * configured backend, publish the backend response to Kafka, and relay that response back to the
 * caller. No content routing — every request goes to the same backend.
 */
@Component
public class GcsvcHandler {

    private static final Logger log = LoggerFactory.getLogger(GcsvcHandler.class);

    private static final Set<String> SENSITIVE_HEADERS =
            Set.of("authorization", "cookie", "set-cookie", "proxy-authorization", "x-api-key");

    private final CorrelationIdExtractor correlationIdExtractor;
    private final ProxyClient proxyClient;
    private final ErrorResponseBuilder errorResponseBuilder;
    private final MetricsService metrics;
    private final TrafficPublisher trafficPublisher;

    public GcsvcHandler(CorrelationIdExtractor correlationIdExtractor,
                        ProxyClient proxyClient,
                        ErrorResponseBuilder errorResponseBuilder,
                        MetricsService metrics,
                        TrafficPublisher trafficPublisher) {
        this.correlationIdExtractor = correlationIdExtractor;
        this.proxyClient = proxyClient;
        this.errorResponseBuilder = errorResponseBuilder;
        this.metrics = metrics;
        this.trafficPublisher = trafficPublisher;
    }

    public Mono<ServerResponse> handle(ServerRequest request) {
        log.info("Incoming request: method={} path={} contentType={} headers={}",
                request.method(), request.path(),
                request.headers().contentType().orElse(null),
                safeHeaders(request));

        String txId = UUID.randomUUID().toString();
        Timer.Sample timerSample = metrics.startTimer();
        metrics.incrementActiveRequests();

        return request.bodyToMono(byte[].class)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Empty request body received");
                    return Mono.empty();
                }))
                .flatMap(body -> {
                    // Content-agnostic: only the correlation id is read, purely to key the Kafka pair.
                    String correlationId = correlationIdExtractor.extract(body);

                    log.info("Proxying request",
                            kv("correlationId", correlationId),
                            kv("bodySize", body.length));

                    metrics.recordRequest();
                    trafficPublisher.publishRequest(txId, correlationId, body);

                    return proxyClient.forward(body, request.headers().asHttpHeaders())
                            .flatMap(result -> {
                                log.info("Backend response received",
                                        kv("correlationId", correlationId),
                                        kv("status", result.status().value()),
                                        kv("responseSize", result.body().length));
                                metrics.stopTimer(timerSample);
                                String outcome = result.status().is2xxSuccessful()
                                        ? "success" : "backend-status-" + result.status().value();
                                trafficPublisher.publishResponse(txId, correlationId, outcome, result.body());
                                // Transparent relay: backend status + headers + body verbatim.
                                return ServerResponse.status(result.status())
                                        .headers(h -> h.addAll(result.headers()))
                                        .bodyValue(result.body());
                            })
                            .onErrorResume(ex -> {
                                // No HTTP response from the backend (connection refused/reset/timeout):
                                // synthesize a gateway error, since there is no backend status to relay.
                                log.error("Backend transport error",
                                        kv("correlationId", correlationId),
                                        kv("error", ex.getMessage()));
                                metrics.recordUpstreamError(ex.getClass().getSimpleName());
                                metrics.stopTimer(timerSample);
                                boolean timedOut = ex instanceof ru.copperside.sbprouter.balancing.AllBackendsFailedException e
                                        && e.lastWasTimeout();
                                HttpStatus status = timedOut ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
                                String errorXml = errorResponseBuilder.buildErrorResponse(null, ex.getMessage());
                                trafficPublisher.publishResponse(txId, correlationId, "backend-error",
                                        errorXml.getBytes(StandardCharsets.UTF_8));
                                return ServerResponse.status(status)
                                        .contentType(MediaType.APPLICATION_XML)
                                        .bodyValue(errorXml);
                            });
                })
                .doFinally(signal -> metrics.decrementActiveRequests());
    }

    private static Map<String, String> safeHeaders(ServerRequest request) {
        return request.headers().asHttpHeaders().toSingleValueMap().entrySet().stream()
                .filter(entry -> !SENSITIVE_HEADERS.contains(entry.getKey().toLowerCase()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
