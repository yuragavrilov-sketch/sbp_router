package ru.copperside.sbprouter.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class MetricsService {
    private final MeterRegistry registry;
    private final AtomicInteger activeRequests;

    public MetricsService(MeterRegistry registry) {
        this.registry = registry;
        this.activeRequests = registry.gauge("sbp_router_active_requests", new AtomicInteger(0));
    }

    public void incrementActiveRequests() { activeRequests.incrementAndGet(); }
    public void decrementActiveRequests() { activeRequests.decrementAndGet(); }

    public void recordRequest() {
        Counter.builder("sbp_router_requests_total")
                .register(registry).increment();
    }

    public Timer.Sample startTimer() { return Timer.start(registry); }

    public void stopTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("sbp_router_request_duration_seconds")
                .register(registry));
    }

    public void recordUpstreamError(String errorType) {
        Counter.builder("sbp_router_upstream_errors_total")
                .tag("errorType", safe(errorType))
                .register(registry).increment();
    }

    public void recordKafkaPublished(String direction) {
        Counter.builder("sbp_router_kafka_published_total")
                .tag("direction", safe(direction))
                .register(registry).increment();
    }

    public void recordKafkaPublishError(String direction) {
        Counter.builder("sbp_router_kafka_publish_errors_total")
                .tag("direction", safe(direction))
                .register(registry).increment();
    }

    /** Point-in-time view of the key meters, for the fleet heartbeat. Missing meters read as 0. */
    public MetricsSnapshot snapshot() {
        double requests = sumCounters("sbp_router_requests_total");
        double errors = sumCounters("sbp_router_upstream_errors_total");
        double kafka = sumCounters("sbp_router_kafka_published_total");
        Timer timer = registry.find("sbp_router_request_duration_seconds").timer();
        long count = timer != null ? timer.count() : 0L;
        double avg = timer != null ? timer.mean(TimeUnit.MILLISECONDS) : 0.0;
        double max = timer != null ? timer.max(TimeUnit.MILLISECONDS) : 0.0;
        return new MetricsSnapshot(activeRequests.get(), requests, errors, kafka, count, avg, max);
    }

    private double sumCounters(String name) {
        return registry.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }

    private String safe(String value) { return value != null ? value : "unknown"; }

    /** Immutable snapshot of router metrics published in each heartbeat. */
    public record MetricsSnapshot(int activeRequests, double requestsTotal, double upstreamErrorsTotal,
                                  double kafkaPublishedTotal, long requestCount, double avgLatencyMs,
                                  double maxLatencyMs) {
    }
}
