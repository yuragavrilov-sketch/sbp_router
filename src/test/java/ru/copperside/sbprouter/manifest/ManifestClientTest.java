package ru.copperside.sbprouter.manifest;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class ManifestClientTest {

    private WireMockServer wireMock;
    private ManifestClient client;

    private static final String PATH = "/internal/v1/sbp-router-management/routing-manifests/latest";

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        ManifestProperties props = new ManifestProperties(true, wireMock.baseUrl(), "secret-key");
        client = new ManifestClient(WebClient.builder().build(), props);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void fetchesAndDeserializesLatestManifest() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("""
                {"data":{"version":3,"checksum":"sha256:abc",
                  "payload":{"upstreams":{"infosrv":{"url":"http://u","timeoutMs":30000,
                    "retry":{"maxAttempts":2,"backoffMs":500}}},
                    "routing":{"tkb-pay-enabled":"true"},
                    "terminals":{"c2bFieldName":"rcvTspId","b2cFieldName":"terminalName",
                      "tkbPayPrefix":"Pay","tkbPayList":["MB1"]},
                    "extractionRules":{}}}}
                """)));

        Optional<ManifestDtos.ManifestDto> dto = client.latest();

        assertThat(dto).isPresent();
        assertThat(dto.get().version()).isEqualTo(3);
        assertThat(dto.get().checksum()).isEqualTo("sha256:abc");
        assertThat(dto.get().payload().upstreams()).containsKey("infosrv");
        assertThat(dto.get().payload().routing()).containsEntry("tkb-pay-enabled", "true");
        wireMock.verify(getRequestedFor(urlEqualTo(PATH))
                .withHeader("X-Internal-Admin-Key", equalTo("secret-key")));
    }

    @Test
    void returnsEmptyOnNotFound() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(404)));
        assertThat(client.latest()).isEmpty();
    }

    @Test
    void returnsEmptyOnServerError() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));
        assertThat(client.latest()).isEmpty();
    }
}
