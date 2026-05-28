package ru.copperside.sbprouter.proxy;

import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import ru.copperside.sbprouter.extraction.ExtractionResult;
import ru.copperside.sbprouter.extraction.XmlFieldExtractor;
import ru.copperside.sbprouter.observability.MetricsService;
import ru.copperside.sbprouter.routing.RouteDecision;
import ru.copperside.sbprouter.routing.RoutingDecisionEngine;
import ru.copperside.sbprouter.routing.TerminalDetector;
import ru.copperside.sbprouter.routing.TerminalOwner;
import ru.copperside.sbprouter.observability.TrafficPublisher;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
public class GcsvcHandler {

    private static final Logger log = LoggerFactory.getLogger(GcsvcHandler.class);

    private static final Set<String> SENSITIVE_HEADERS =
            Set.of("authorization", "cookie", "set-cookie", "proxy-authorization", "x-api-key");

    private final XmlFieldExtractor extractor;
    private final TerminalDetector terminalDetector;
    private final RoutingDecisionEngine routingEngine;
    private final ProxyClient proxyClient;
    private final ErrorResponseBuilder errorResponseBuilder;
    private final MetricsService metrics;
    private final TrafficPublisher trafficPublisher;

    public GcsvcHandler(XmlFieldExtractor extractor,
                        TerminalDetector terminalDetector,
                        RoutingDecisionEngine routingEngine,
                        ProxyClient proxyClient,
                        ErrorResponseBuilder errorResponseBuilder,
                        MetricsService metrics,
                        TrafficPublisher trafficPublisher) {
        this.extractor = extractor;
        this.terminalDetector = terminalDetector;
        this.routingEngine = routingEngine;
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
                    log.debug("Request body size: {} bytes", body.length);

                    ExtractionResult extraction;
                    try {
                        extraction = extractor.extract(body);
                    } catch (Exception e) {
                        log.error("Failed to parse XML", e);
                        return ServerResponse.badRequest()
                                .contentType(MediaType.APPLICATION_XML)
                                .bodyValue(errorResponseBuilder.buildErrorResponse(null, "Invalid XML: " + e.getMessage()));
                    }

                    if (extraction.requestType() == null) {
                        log.warn("Unknown request type in XML, correlationId={}",
                                extraction.correlationId());
                    }

                    TerminalOwner owner = terminalDetector.detect(extraction.fields());
                    RouteDecision decision = routingEngine.decide(extraction, owner);

                    log.info("Routing request",
                            kv("correlationId", extraction.correlationId()),
                            kv("requestType", extraction.requestType()),
                            kv("terminalOwner", owner.name()),
                            kv("routeDecision", decision.upstreamName()));

                    metrics.recordRequest(extraction.requestType(), owner.name(), decision.upstreamName());
                    trafficPublisher.publishRequest(txId, extraction.correlationId(), extraction.requestType(),
                            owner.name(), decision.upstreamName(), body);

                    Map<String, String> extraHeaders = new HashMap<>(extraction.extraFields());
                    if (extraction.correlationId() != null) {
                        extraHeaders.put("correlationId", extraction.correlationId());
                    }

                    return proxyClient.forward(decision.upstreamName(), body, extraHeaders)
                            .flatMap(responseBody -> {
                                log.info("Upstream response received",
                                        kv("correlationId", extraction.correlationId()),
                                        kv("upstream", decision.upstreamName()),
                                        kv("responseSize", responseBody.length));
                                metrics.stopTimer(timerSample, extraction.requestType(), decision.upstreamName());
                                trafficPublisher.publishResponse(txId, extraction.correlationId(),
                                        extraction.requestType(), decision.upstreamName(), "success", responseBody);
                                return ServerResponse.ok()
                                        .contentType(MediaType.APPLICATION_XML)
                                        .bodyValue(responseBody);
                            })
                            .onErrorResume(ex -> {
                                log.error("Upstream error",
                                        kv("correlationId", extraction.correlationId()),
                                        kv("upstream", decision.upstreamName()),
                                        kv("error", ex.getMessage()));
                                metrics.recordUpstreamError(
                                        extraction.requestType(), decision.upstreamName(), ex.getClass().getSimpleName());
                                metrics.stopTimer(timerSample, extraction.requestType(), decision.upstreamName());
                                String errorXml = errorResponseBuilder.buildErrorResponse(
                                        extraction.requestType(), ex.getMessage());
                                trafficPublisher.publishResponse(txId, extraction.correlationId(),
                                        extraction.requestType(), decision.upstreamName(), "upstream-error",
                                        errorXml.getBytes(StandardCharsets.UTF_8));
                                return ServerResponse.ok()
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
