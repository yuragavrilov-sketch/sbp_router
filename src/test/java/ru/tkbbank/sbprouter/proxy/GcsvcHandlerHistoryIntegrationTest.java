package ru.tkbbank.sbprouter.proxy;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import ru.tkbbank.sbprouter.history.RequestHistoryStore;

import java.io.IOException;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class GcsvcHandlerHistoryIntegrationTest {
    static WireMockServer wireMock = new WireMockServer(0);
    @Autowired WebTestClient webClient;
    @Autowired RequestHistoryStore history;

    @DynamicPropertySource static void upstream(DynamicPropertyRegistry r) {
        wireMock.start(); r.add("sbp-router.upstreams.infosrv.url", () -> wireMock.baseUrl() + "/api/gcsvc");
    }
    @AfterAll static void stop() { wireMock.stop(); }
    @BeforeEach void reset() { wireMock.resetAll(); }

    @Test void recordsSuccessfulRequestInHistory() throws IOException {
        wireMock.stubFor(post(urlEqualTo("/api/gcsvc")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<Document><GCSvc><Payment><AnsAuthPay><Status><Code>0</Code></Status></AnsAuthPay></Payment></GCSvc></Document>")));
        int before = history.size();
        byte[] xml = getClass().getClassLoader().getResourceAsStream("test-xml/req-auth-pay-b2c.xml").readAllBytes();
        webClient.post().uri("/api/gcsvc").contentType(MediaType.APPLICATION_XML).accept(MediaType.APPLICATION_XML)
                .bodyValue(xml).exchange().expectStatus().isOk();
        assertThat(history.size()).isGreaterThan(before);
        var latest = history.recent(1).get(0);
        assertThat(latest.requestType()).isEqualTo("ReqAuthPay");
        assertThat(latest.routeDecision()).isEqualTo("infosrv");
        assertThat(latest.upstreamStatusCode()).isEqualTo(200);
        assertThat(latest.durationMs()).isGreaterThanOrEqualTo(0);
    }
}
