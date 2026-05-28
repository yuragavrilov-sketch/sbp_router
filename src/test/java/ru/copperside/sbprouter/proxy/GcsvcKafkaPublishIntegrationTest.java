package ru.copperside.sbprouter.proxy;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = "sbp-router-traffic")
class GcsvcKafkaPublishIntegrationTest {

    private static final String TOPIC = "sbp-router-traffic";

    static WireMockServer wireMock = new WireMockServer(0);

    @Autowired
    private WebTestClient webClient;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        wireMock.start();
        registry.add("sbp-router.upstreams.infosrv.url", () -> wireMock.baseUrl() + "/api/gcsvc");
        registry.add("sbp-router.kafka.enabled", () -> "true");
        registry.add("sbp-router.kafka.bootstrap-servers",
                () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    static void tearDown() {
        wireMock.stop();
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    void publishesRequestAndResponseEvents() throws IOException {
        wireMock.stubFor(post(urlEqualTo("/api/gcsvc")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/xml")
                .withBody("<Document><GCSvc><Payment><AnsAuthPay><Status><Code>0</Code></Status></AnsAuthPay></Payment></GCSvc></Document>")));

        byte[] requestXml = loadFixture("test-xml/req-auth-pay-b2c.xml");
        webClient.post().uri("/api/gcsvc")
                .contentType(MediaType.APPLICATION_XML)
                .bodyValue(requestXml)
                .exchange().expectStatus().isOk();

        List<ConsumerRecord<String, byte[]>> records = consumeUntil(seen -> seen.stream().anyMatch(
                r -> "request".equals(headerValue(r, "direction")) && Arrays.equals(r.value(), requestXml)));

        ConsumerRecord<String, byte[]> req = lastMatching(records,
                r -> "request".equals(headerValue(r, "direction")) && Arrays.equals(r.value(), requestXml));
        Assertions.assertNotNull(req, "request event missing");
        ConsumerRecord<String, byte[]> resp = lastMatching(records,
                r -> "response".equals(headerValue(r, "direction")) && req.key().equals(r.key()));
        Assertions.assertNotNull(resp, "response event missing");

        Assertions.assertEquals("ReqAuthPay", headerValue(req, "requestType"));
        Assertions.assertEquals("infosrv", headerValue(req, "route"));
        Assertions.assertEquals("success", headerValue(resp, "outcome"));
        Assertions.assertEquals("infosrv", headerValue(resp, "upstream"));
    }

    @Test
    void publishesRawBodyAndParseErrorOnInvalidXml() {
        byte[] invalid = "this is not xml".getBytes(StandardCharsets.UTF_8);
        webClient.post().uri("/api/gcsvc")
                .contentType(MediaType.APPLICATION_XML)
                .bodyValue(invalid)
                .exchange().expectStatus().isBadRequest();

        List<ConsumerRecord<String, byte[]>> records = consumeUntil(seen -> seen.stream().anyMatch(
                r -> "request".equals(headerValue(r, "direction")) && Arrays.equals(r.value(), invalid)));

        ConsumerRecord<String, byte[]> req = lastMatching(records,
                r -> "request".equals(headerValue(r, "direction")) && Arrays.equals(r.value(), invalid));
        Assertions.assertNotNull(req, "request event missing");
        ConsumerRecord<String, byte[]> resp = lastMatching(records,
                r -> "response".equals(headerValue(r, "direction")) && req.key().equals(r.key()));
        Assertions.assertNotNull(resp, "response event missing");

        Assertions.assertEquals("unknown", headerValue(req, "requestType"));
        Assertions.assertNull(req.headers().lastHeader("route"));
        Assertions.assertEquals("parse-error", headerValue(resp, "outcome"));
    }

    private List<ConsumerRecord<String, byte[]>> consumeUntil(Predicate<List<ConsumerRecord<String, byte[]>>> done) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.embedded.kafka.brokers"));
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, byte[]>> all = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer =
                     new KafkaConsumer<>(cfg, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.currentTimeMillis() + 15000;
            while (!done.test(all) && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, byte[]> polled = consumer.poll(Duration.ofMillis(500));
                polled.forEach(all::add);
            }
        }
        return all;
    }

    private static ConsumerRecord<String, byte[]> lastMatching(
            List<ConsumerRecord<String, byte[]>> records, Predicate<ConsumerRecord<String, byte[]>> match) {
        return records.stream().filter(match).reduce((a, b) -> b).orElse(null);
    }

    private static String headerValue(ConsumerRecord<String, byte[]> r, String key) {
        Header h = r.headers().lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private byte[] loadFixture(String path) throws IOException {
        try (var is = getClass().getClassLoader().getResourceAsStream(path)) {
            return is.readAllBytes();
        }
    }
}
