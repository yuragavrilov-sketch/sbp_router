package ru.copperside.sbprouter.proxy;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class GcsvcKafkaUnavailableIntegrationTest {

    static WireMockServer wireMock = new WireMockServer(0);

    @Autowired
    private WebTestClient webClient;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        wireMock.start();
        registry.add("sbp-router.backend.url", () -> wireMock.baseUrl() + "/api/gcsvc");
        registry.add("sbp-router.kafka.enabled", () -> "true");
        // Unreachable broker: nothing listens on this port.
        registry.add("sbp-router.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @AfterAll
    static void tearDown() {
        wireMock.stop();
    }

    @Test
    void proxyStillRespondsWhenBrokerUnreachable() throws IOException {
        wireMock.stubFor(post(urlEqualTo("/api/gcsvc")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/xml")
                .withBody("<Document><GCSvc><Payment><AnsAuthPay><Status><Code>0</Code></Status></AnsAuthPay></Payment></GCSvc></Document>")));

        byte[] requestXml = loadFixture("test-xml/req-auth-pay-b2c.xml");

        String body = webClient.mutate()
                .responseTimeout(Duration.ofSeconds(10))
                .build()
                .post().uri("/api/gcsvc")
                .contentType(MediaType.APPLICATION_XML)
                .bodyValue(requestXml)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        Assertions.assertNotNull(body);
        Assertions.assertTrue(body.contains("<Code>0</Code>"),
                "proxy must return the upstream success body even when Kafka is down");
    }

    private byte[] loadFixture(String path) throws IOException {
        try (var is = getClass().getClassLoader().getResourceAsStream(path)) {
            return is.readAllBytes();
        }
    }
}
