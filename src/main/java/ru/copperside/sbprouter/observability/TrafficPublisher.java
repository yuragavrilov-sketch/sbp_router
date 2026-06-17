package ru.copperside.sbprouter.observability;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import ru.copperside.sbprouter.config.SbpRouterProperties;
import ru.copperside.sbprouter.extraction.GcsvcMessageInfo;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class TrafficPublisher {

    private static final Logger log = LoggerFactory.getLogger(TrafficPublisher.class);

    private final KafkaSender<String, byte[]> sender; // null when Kafka disabled
    private final SbpRouterProperties properties;
    private final MetricsService metrics;
    private final String environment;

    public TrafficPublisher(ObjectProvider<KafkaSender<String, byte[]>> senderProvider,
                            SbpRouterProperties properties,
                            MetricsService metrics,
                            @Value("${pay.environment:local}") String env) {
        this.sender = senderProvider.getIfAvailable();
        this.properties = properties;
        this.metrics = metrics;
        this.environment = env;
    }

    public void publishRequest(String txId, GcsvcMessageInfo info, byte[] body) {
        publish("request", txId, info, null, body);
    }

    public void publishResponse(String txId, GcsvcMessageInfo info, String outcome, byte[] body) {
        publish("response", txId, info, outcome, body);
    }

    private void publish(String direction, String txId, GcsvcMessageInfo info, String outcome, byte[] body) {
        if (sender == null) {
            return; // Kafka disabled — no-op
        }
        String correlationId = info != null ? info.correlationId() : null;
        String key = correlationId != null ? correlationId : txId;
        List<Header> headers = headersFor(direction, txId, info, environment);
        if (outcome != null) {
            headers.add(header("outcome", outcome));
        }
        String topic = properties.getKafka().getTopic();
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, null, key, body, headers);
        sender.send(Mono.just(SenderRecord.create(record, txId)))
                .doOnNext(result -> metrics.recordKafkaPublished(direction))
                .onErrorResume(e -> {
                    log.warn("Kafka publish failed direction={} key={}: {}", direction, key, e.toString());
                    metrics.recordKafkaPublishError(direction);
                    return Mono.empty();
                })
                .subscribe();
    }

    /** Builds the base traffic headers (null values skipped). Package-private for tests. */
    static List<Header> headersFor(String direction, String txId, GcsvcMessageInfo info, String env) {
        List<Header> headers = new ArrayList<>();
        headers.add(header("direction", direction));
        headers.add(header("txId", txId));
        addIfPresent(headers, "correlationId", info == null ? null : info.correlationId());
        addIfPresent(headers, "requestType", info == null ? null : info.messageType());
        addIfPresent(headers, "operationId", info == null ? null : info.operationId());
        addIfPresent(headers, "operationType", info == null ? null : info.operationType());
        addIfPresent(headers, "env", env);
        headers.add(header("timestamp", Instant.now().toString()));
        return headers;
    }

    private static void addIfPresent(List<Header> headers, String key, String value) {
        if (value != null && !value.isBlank()) {
            headers.add(header(key, value));
        }
    }

    private static Header header(String key, String value) {
        return new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8));
    }
}
