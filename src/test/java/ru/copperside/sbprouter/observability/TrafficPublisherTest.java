package ru.copperside.sbprouter.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import reactor.kafka.sender.SenderResult;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TrafficPublisherTest {

    private SbpRouterProperties properties;
    private MetricsService metrics;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new SbpRouterProperties();
        properties.getKafka().setTopic("sbp-router-traffic");
        registry = new SimpleMeterRegistry();
        metrics = new MetricsService(registry);
    }

    @SuppressWarnings("unchecked")
    private TrafficPublisher publisherWith(KafkaSender<String, byte[]> sender) {
        ObjectProvider<KafkaSender<String, byte[]>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return new TrafficPublisher(provider, properties, metrics, "compose");
    }

    @SuppressWarnings("unchecked")
    private static KafkaSender<String, byte[]> senderReturning(Flux<SenderResult<String>> result) {
        KafkaSender<String, byte[]> sender = mock(KafkaSender.class);
        when(sender.send(any(org.reactivestreams.Publisher.class))).thenReturn(result);
        return sender;
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsRequestRecordWithHeaders() {
        KafkaSender<String, byte[]> sender = senderReturning(Flux.empty());
        TrafficPublisher publisher = publisherWith(sender);

        byte[] body = "<xml/>".getBytes(StandardCharsets.UTF_8);
        publisher.publishRequest("tx-1", "corr-1", "ReqAuthPay", "EXTERNAL", "infosrv", body);

        ArgumentCaptor<Mono<SenderRecord<String, byte[], String>>> captor = ArgumentCaptor.forClass(Mono.class);
        verify(sender).send(captor.capture());
        SenderRecord<String, byte[], String> sent = captor.getValue().block();
        ProducerRecord<String, byte[]> record = sent;

        assertThat(record.topic()).isEqualTo("sbp-router-traffic");
        assertThat(record.key()).isEqualTo("corr-1");
        assertThat(record.value()).isEqualTo(body);
        assertThat(headerValue(record, "direction")).isEqualTo("request");
        assertThat(headerValue(record, "txId")).isEqualTo("tx-1");
        assertThat(headerValue(record, "correlationId")).isEqualTo("corr-1");
        assertThat(headerValue(record, "requestType")).isEqualTo("ReqAuthPay");
        assertThat(headerValue(record, "terminalOwner")).isEqualTo("EXTERNAL");
        assertThat(headerValue(record, "route")).isEqualTo("infosrv");
        assertThat(headerValue(record, "env")).isEqualTo("compose");
        assertThat(headerValue(record, "timestamp")).isNotBlank();
    }

    @Test
    void responseRecordHasUpstreamAndOutcome() {
        KafkaSender<String, byte[]> sender = senderReturning(Flux.empty());
        TrafficPublisher publisher = publisherWith(sender);

        publisher.publishResponse("tx-2", "corr-2", "ReqNoticePay", "infosrv", "success",
                "<ok/>".getBytes(StandardCharsets.UTF_8));

        ProducerRecord<String, byte[]> record = capturedRecord(sender);
        assertThat(headerValue(record, "direction")).isEqualTo("response");
        assertThat(headerValue(record, "upstream")).isEqualTo("infosrv");
        assertThat(headerValue(record, "outcome")).isEqualTo("success");
        assertThat(record.key()).isEqualTo("corr-2");
    }

    @Test
    void keyFallsBackToTxIdWhenCorrelationNull() {
        KafkaSender<String, byte[]> sender = senderReturning(Flux.empty());
        TrafficPublisher publisher = publisherWith(sender);

        publisher.publishRequest("tx-3", null, null, null, null,
                "<bad".getBytes(StandardCharsets.UTF_8));

        ProducerRecord<String, byte[]> record = capturedRecord(sender);
        assertThat(record.key()).isEqualTo("tx-3");
        assertThat(headerValue(record, "requestType")).isEqualTo("unknown");
        assertThat(record.headers().lastHeader("correlationId")).isNull();
        assertThat(record.headers().lastHeader("terminalOwner")).isNull();
        assertThat(record.headers().lastHeader("route")).isNull();
    }

    @Test
    void fireAndForget_doesNotThrow_andCountsError_whenSendFails() {
        KafkaSender<String, byte[]> sender = senderReturning(Flux.error(new RuntimeException("broker down")));
        TrafficPublisher publisher = publisherWith(sender);

        assertThatCode(() -> publisher.publishResponse("tx-4", "c", "ReqAuthPay", "infosrv", "success",
                "<x/>".getBytes(StandardCharsets.UTF_8))).doesNotThrowAnyException();

        assertThat(registry.get("sbp_router_kafka_publish_errors_total")
                .tag("direction", "response").counter().count()).isEqualTo(1.0);
    }

    @Test
    void noOpWhenSenderAbsent() {
        TrafficPublisher publisher = publisherWith(null);

        assertThatCode(() -> publisher.publishRequest("tx-5", "c", "ReqAuthPay", "EXTERNAL", "infosrv",
                "<x/>".getBytes(StandardCharsets.UTF_8))).doesNotThrowAnyException();
        assertThat(registry.find("sbp_router_kafka_published_total").counter()).isNull();
    }

    @SuppressWarnings("unchecked")
    private static ProducerRecord<String, byte[]> capturedRecord(KafkaSender<String, byte[]> sender) {
        ArgumentCaptor<Mono<SenderRecord<String, byte[], String>>> captor = ArgumentCaptor.forClass(Mono.class);
        verify(sender).send(captor.capture());
        return captor.getValue().block();
    }

    private static String headerValue(ProducerRecord<String, byte[]> record, String key) {
        var h = record.headers().lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
