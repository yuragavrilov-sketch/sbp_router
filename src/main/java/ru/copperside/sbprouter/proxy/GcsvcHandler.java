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
import ru.copperside.sbprouter.balancing.AuthPayRoute;
import ru.copperside.sbprouter.balancing.BackendGroupRegistry;
import ru.copperside.sbprouter.extraction.CorrelationIdExtractor;
import ru.copperside.sbprouter.extraction.GcsvcMessageInfo;
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
    private final BackendGroupRegistry registry;

    public GcsvcHandler(CorrelationIdExtractor correlationIdExtractor,
                        ProxyClient proxyClient,
                        ErrorResponseBuilder errorResponseBuilder,
                        MetricsService metrics,
                        TrafficPublisher trafficPublisher,
                        BackendGroupRegistry registry) {
        this.correlationIdExtractor = correlationIdExtractor;
        this.proxyClient = proxyClient;
        this.errorResponseBuilder = errorResponseBuilder;
        this.metrics = metrics;
        this.trafficPublisher = trafficPublisher;
        this.registry = registry;
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
                    GcsvcMessageInfo info = correlationIdExtractor.extractMessageInfo(body);
                    String correlationId = info.correlationId();

                    AuthPayRoute authPayRoute = registry.authPayRoute();
                    boolean toAuthPay = authPayRoute.enabled()
                            && "ReqAuthPay".equals(info.messageType())
                            && (authPayRoute.sbpOperations().isEmpty()
                                || authPayRoute.sbpOperations().contains(info.sbpOperation()));

                    log.debug("AuthPay decision: enabled={} messageType={} sbpOperation={} sbpOperations={} toAuthPay={}",
                            authPayRoute.enabled(), info.messageType(), info.sbpOperation(),
                            authPayRoute.sbpOperations(), toAuthPay);

                    log.info("Proxying request",
                            kv("correlationId", correlationId),
                            kv("messageType", info.messageType()),
                            kv("route", toAuthPay ? "authpay" : "group"),
                            kv("bodySize", body.length));

                    metrics.recordRequest();
                    trafficPublisher.publishRequest(txId, info, body);

                    Mono<ProxyClient.ProxyResult> forward = toAuthPay
                            ? proxyClient.forward(body, request.headers().asHttpHeaders(),
                                    authPayRoute.pool(), authPayRoute.timeout())
                            : proxyClient.forward(body, request.headers().asHttpHeaders());

                    return forward
                            .flatMap(result -> {
                                metrics.stopTimer(timerSample);
                                String outcome = result.status().is2xxSuccessful()
                                        ? (toAuthPay ? "authpay-ok" : "success")
                                        : (toAuthPay ? "authpay-status-" + result.status().value()
                                                     : "backend-status-" + result.status().value());
                                trafficPublisher.publishResponse(txId, info, outcome, result.body());
                                return ServerResponse.status(result.status())
                                        .headers(h -> h.addAll(result.headers()))
                                        .bodyValue(result.body());
                            })
                            .onErrorResume(ex -> {
                                metrics.recordUpstreamError(ex.getClass().getSimpleName());
                                metrics.stopTimer(timerSample);
                                boolean timedOut = ex instanceof ru.copperside.sbprouter.balancing.AllBackendsFailedException e
                                        && e.lastWasTimeout();
                                HttpStatus status = timedOut ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
                                // Fail-closed for AuthPay: synthesize an AnsAuthPay refusal; never fall through
                                // to the main group. (buildErrorResponse(null) already yields AnsAuthPay.)
                                String errorXml = errorResponseBuilder.buildErrorResponse(info.messageType(), ex.getMessage());
                                trafficPublisher.publishResponse(txId, info,
                                        toAuthPay ? "authpay-unavailable" : "backend-error",
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
