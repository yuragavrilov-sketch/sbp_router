package ru.copperside.sbprouter.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
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
import ru.copperside.sbprouter.extraction.GcsvcMessageInfo;

import java.nio.charset.StandardCharsets;
import java.util.List;

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

    // ── headersFor static helper ────────────────────────────────────────────

    @Test
    void headersIncludeOperationFields() {
        GcsvcMessageInfo info = new GcsvcMessageInfo("stan-1", "ReqAuthPay", "OP-1", "C2BQRS_Rcv", "C2B");
        List<Header> hs = TrafficPublisher.headersFor("request", "tx-1", info, "compose");
        assertThat(headerValue(hs, "correlationId")).isEqualTo("stan-1");
        assertThat(headerValue(hs, "operationId")).isEqualTo("OP-1");
        assertThat(headerValue(hs, "operationType")).isEqualTo("C2B");
        assertThat(headerValue(hs, "requestType")).isEqualTo("ReqAuthPay");
        assertThat(headerValue(hs, "direction")).isEqualTo("request");
    }

    @Test
    void headersFor_skipsNullOperationFields() {
        GcsvcMessageInfo info = new GcsvcMessageInfo("stan-2", "ReqNoticePay", null, null, null);
        List<Header> hs = TrafficPublisher.headersFor("request", "tx-2", info, "compose");
        assertThat(headerValue(hs, "correlationId")).isEqualTo("stan-2");
        assertThat(headerValue(hs, "requestType")).isEqualTo("ReqNoticePay");
        // null fields must not produce headers
        assertThat(hs.stream().noneMatch(h -> h.key().equals("operationId"))).isTrue();
        assertThat(hs.stream().noneMatch(h -> h.key().equals("operationType"))).isTrue();
    }

    // ── publishRequest / publishResponse end-to-end ────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void buildsRequestRecordWithHeaders() {
        KafkaSender<String, byte[]> sender = senderReturning(Flux.empty());
        TrafficPublisher publisher = publisherWith(sender);

        GcsvcMessageInfo info = new GcsvcMessageInfo("corr-1", "ReqAuthPay", "OP-X", "C2BQRS_Rcv", "C2B");
        byte[] body = "<xml/>".getBytes(StandardCharsets.UTF_8);
        publisher.publishRequest("tx-1", info, body);

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
        assertThat(headerValue(record, "operationId")).isEqualTo("OP-X");
        assertThat(headerValue(record, "operationType")).isEqualTo("C2B");
        assertThat(headerValue(record, "requestType")).isEqualTo("ReqAuthPay");
        assertThat(headerValue(record, "env")).isEqualTo("compose");
        assertThat(headerValue(record, "timestamp")).isNotBlank();
    }

    @Test
    void responseRecordHasOutcome() {
        KafkaSender<String, byte[]> sender = senderReturning(Flux.empty());
        TrafficPublisher publisher = publisherWith(sender);

        GcsvcMessageInfo info = new GcsvcMessageInfo("corr-2", "AnsAuthPay", null, null, null);
        publisher.publishResponse("tx-2", info, "success",
                "<ok/>".getBytes(StandardCharsets.UTF_8));

        ProducerRecord<String, byte[]> record = capturedRecord(sender);
        assertThat(headerValue(record, "direction")).isEqualTo("response");
        assertThat(headerValue(record, "outcome")).isEqualTo("success");
        assertThat(record.key()).isEqualTo("corr-2");
    }

    @Test
    void keyFallsBackToTxIdWhenCorrelationNull() {
        KafkaSender<String, byte[]> sender = senderReturning(Flux.empty());
        TrafficPublisher publisher = publisherWith(sender);

        GcsvcMessageInfo info = new GcsvcMessageInfo(null, null, null, null, null);
        publisher.publishRequest("tx-3", info, "<bad".getBytes(StandardCharsets.UTF_8));

        ProducerRecord<String, byte[]> record = capturedRecord(sender);
        assertThat(record.key()).isEqualTo("tx-3");
        assertThat(record.headers().lastHeader("correlationId")).isNull();
    }

    @Test
    void fireAndForget_doesNotThrow_andCountsError_whenSendFails() {
        KafkaSender<String, byte[]> sender = senderReturning(Flux.error(new RuntimeException("broker down")));
        TrafficPublisher publisher = publisherWith(sender);

        GcsvcMessageInfo info = new GcsvcMessageInfo("c", null, null, null, null);
        assertThatCode(() -> publisher.publishResponse("tx-4", info, "success",
                "<x/>".getBytes(StandardCharsets.UTF_8))).doesNotThrowAnyException();

        assertThat(registry.get("sbp_router_kafka_publish_errors_total")
                .tag("direction", "response").counter().count()).isEqualTo(1.0);
    }

    @Test
    void noOpWhenSenderAbsent() {
        TrafficPublisher publisher = publisherWith(null);

        GcsvcMessageInfo info = new GcsvcMessageInfo("c", null, null, null, null);
        assertThatCode(() -> publisher.publishRequest("tx-5", info,
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

    private static String headerValue(List<Header> hs, String k) {
        return hs.stream().filter(h -> h.key().equals(k))
                .map(h -> new String(h.value(), StandardCharsets.UTF_8))
                .findFirst().orElse(null);
    }
}
