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

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class AuthPayRoutingIntegrationTest {

    static WireMockServer wireMock = new WireMockServer(0);

    @Autowired
    private WebTestClient webClient;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        wireMock.start();
        registry.add("sbp-router.groups.default.backends[0]", () -> wireMock.baseUrl() + "/api/gcsvc");
        registry.add("sbp-router.auth-pay.enabled", () -> "true");
        registry.add("sbp-router.auth-pay.backends[0]", () -> wireMock.baseUrl() + "/authpay");
        registry.add("sbp-router.auth-pay.sbp-operations[0]", () -> "C2BQRS_Rcv");
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
    void routesReqAuthPayToAuthPayPool() throws IOException {
        wireMock.stubFor(post(urlEqualTo("/authpay")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<Document><GCSvc><Payment><AnsAuthPay><Status><Code>0</Code></Status>"
                        + "<BankOperId>01890a5d-ac96-774b-8c1f-3e2a9b7d0e4f</BankOperId></AnsAuthPay></Payment></GCSvc></Document>")));

        String body = webClient.post().uri("/api/gcsvc")
                .contentType(MediaType.APPLICATION_XML)
                .bodyValue(loadFixture("test-xml/req-auth-pay-c2b-rcv.xml"))
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        Assertions.assertTrue(body.contains("<Code>0</Code>"));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/authpay")));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/gcsvc")));
    }

    @Test
    void routesReqNoticePayToActiveGroup() throws IOException {
        wireMock.stubFor(post(urlEqualTo("/api/gcsvc")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<Document><GCSvc><Payment><AnsNoticePay><BankOperId>x</BankOperId></AnsNoticePay></Payment></GCSvc></Document>")));

        webClient.post().uri("/api/gcsvc")
                .contentType(MediaType.APPLICATION_XML)
                .bodyValue(loadFixture("test-xml/req-notice-pay-c2b.xml"))
                .exchange().expectStatus().isOk();

        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/gcsvc")));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/authpay")));
    }

    @Test
    void failsClosedWhenAuthPayPoolUnavailable() throws IOException {
        wireMock.stubFor(post(urlEqualTo("/authpay"))
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        String body = webClient.post().uri("/api/gcsvc")
                .contentType(MediaType.APPLICATION_XML)
                .bodyValue(loadFixture("test-xml/req-auth-pay-c2b-rcv.xml"))
                .exchange().expectStatus().isEqualTo(502)
                .expectBody(String.class).returnResult().getResponseBody();

        Assertions.assertTrue(body.contains("<AnsAuthPay>"));
        Assertions.assertTrue(body.contains("<Code>-1</Code>"));
        // Fail-closed: the main backend group must NOT be called.
        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/gcsvc")));
    }

    @Test
    void routesReqAuthPayToAuthPayPool_onlyWhenSbpOperationMatches() throws IOException {
        wireMock.stubFor(post(urlEqualTo("/authpay")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<Document><GCSvc><Payment><AnsAuthPay><Status><Code>0</Code></Status></AnsAuthPay></Payment></GCSvc></Document>")));
        wireMock.stubFor(post(urlEqualTo("/api/gcsvc")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml").withBody("<ok/>")));

        // C2BQRS_Rcv -> AuthPay pool
        webClient.post().uri("/api/gcsvc").contentType(MediaType.APPLICATION_XML)
                .bodyValue(loadFixture("test-xml/req-auth-pay-c2b-rcv.xml"))
                .exchange().expectStatus().isOk();
        wireMock.verify(1, postRequestedFor(urlEqualTo("/authpay")));
    }

    @Test
    void reqAuthPayWithNonMatchingSbpOperation_goesToActiveGroup() throws IOException {
        wireMock.stubFor(post(urlEqualTo("/authpay")).willReturn(aResponse().withStatus(200).withBody("<x/>")));
        wireMock.stubFor(post(urlEqualTo("/api/gcsvc")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml").withBody("<ok/>")));

        // req-auth-pay-b2c.xml has B2COther_Snd -> NOT in sbpOperations -> NOT routed to AuthPay
        webClient.post().uri("/api/gcsvc").contentType(MediaType.APPLICATION_XML)
                .bodyValue(loadFixture("test-xml/req-auth-pay-b2c.xml"))
                .exchange().expectStatus().isOk();
        wireMock.verify(0, postRequestedFor(urlEqualTo("/authpay")));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/gcsvc")));
    }

    private byte[] loadFixture(String path) throws IOException {
        try (var is = getClass().getClassLoader().getResourceAsStream(path)) {
            return is.readAllBytes();
        }
    }
}
