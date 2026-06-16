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
import ru.copperside.sbprouter.balancing.BackendGroupRegistry;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class ProxyFailoverIntegrationTest {

    static WireMockServer b1 = new WireMockServer(0);
    static WireMockServer b2 = new WireMockServer(0);

    @Autowired
    WebTestClient web;

    @Autowired
    BackendGroupRegistry registry;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        b1.start();
        b2.start();
        r.add("sbp-router.groups.default.backends[0]", () -> b1.baseUrl() + "/api/gcsvc");
        r.add("sbp-router.groups.default.backends[1]", () -> b2.baseUrl() + "/api/gcsvc");
        r.add("sbp-router.timeout", () -> "300ms");
        r.add("sbp-router.failover.max-attempts", () -> "2");
        r.add("sbp-router.circuit-breaker.failure-threshold", () -> "1");
        r.add("sbp-router.circuit-breaker.ban-duration", () -> "30s");
    }

    @AfterAll
    static void stop() { b1.stop(); b2.stop(); }

    @BeforeEach
    void reset() {
        b1.resetAll();
        b2.resetAll();
        // Reset per-backend health so bans from previous tests don't bleed over
        // (BackendGroupRegistry is a singleton; health is in-memory and not reset between tests).
        registry.groups().values()
                .forEach(g -> g.backends().forEach(b -> b.health().recordSuccess()));
    }

    @Test
    void failsOverWhenFirstTriedBackendTimesOut() {
        b1.stubFor(post(urlEqualTo("/api/gcsvc")).willReturn(aResponse().withFixedDelay(2000).withStatus(200)));
        b2.stubFor(post(urlEqualTo("/api/gcsvc")).willReturn(aResponse().withStatus(200).withBody("<ok/>")));

        // Two requests so that whichever backend RR picks first, at least one exercises the timeout->failover path.
        for (int i = 0; i < 2; i++) {
            web.post().uri("/api/gcsvc").contentType(MediaType.APPLICATION_XML)
                    .bodyValue("<x/>".getBytes())
                    .exchange().expectStatus().isOk();
        }
        // b2 healthy served at least once; b1 (threshold=1) is banned after its first timeout.
        b2.verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo("/api/gcsvc")));
    }

    @Test
    void returns504WhenAllBackendsTimeOut() {
        b1.stubFor(post(urlEqualTo("/api/gcsvc")).willReturn(aResponse().withFixedDelay(2000).withStatus(200)));
        b2.stubFor(post(urlEqualTo("/api/gcsvc")).willReturn(aResponse().withFixedDelay(2000).withStatus(200)));

        web.post().uri("/api/gcsvc").contentType(MediaType.APPLICATION_XML)
                .bodyValue("<x/>".getBytes())
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody(String.class).value(b -> org.assertj.core.api.Assertions.assertThat(b).contains("<Code>-1</Code>"));
    }
}
