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
    private final String env;

    public TrafficPublisher(ObjectProvider<KafkaSender<String, byte[]>> senderProvider,
                            SbpRouterProperties properties,
                            MetricsService metrics,
                            @Value("${pay.environment:local}") String env) {
        this.sender = senderProvider.getIfAvailable();
        this.properties = properties;
        this.metrics = metrics;
        this.env = env;
    }

    public void publishRequest(String txId, String correlationId, byte[] body) {
        List<Header> headers = baseHeaders("request", txId, correlationId);
        publish("request", txId, correlationId, body, headers);
    }

    public void publishResponse(String txId, String correlationId, String outcome, byte[] body) {
        List<Header> headers = baseHeaders("response", txId, correlationId);
        headers.add(header("outcome", outcome != null ? outcome : "unknown"));
        publish("response", txId, correlationId, body, headers);
    }

    private void publish(String direction, String txId, String correlationId,
                         byte[] body, List<Header> headers) {
        if (sender == null) {
            return; // Kafka disabled — no-op
        }
        String key = correlationId != null ? correlationId : txId;
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

    private List<Header> baseHeaders(String direction, String txId, String correlationId) {
        List<Header> headers = new ArrayList<>();
        headers.add(header("direction", direction));
        headers.add(header("txId", txId));
        if (correlationId != null) {
            headers.add(header("correlationId", correlationId));
        }
        headers.add(header("env", env));
        headers.add(header("timestamp", Instant.now().toString()));
        return headers;
    }

    private static Header header(String key, String value) {
        return new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8));
    }
}
