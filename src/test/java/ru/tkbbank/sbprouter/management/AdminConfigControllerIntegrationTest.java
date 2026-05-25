package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import ru.tkbbank.sbprouter.management.dto.ConfigSnapshotDto;
import ru.tkbbank.sbprouter.management.dto.RoutingConfigDto;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@TestPropertySource(properties = {"sbp-router.admin.token=test-token", "sbp-router.config.override-path=target/test-overrides.json"})
class AdminConfigControllerIntegrationTest {
    @Autowired WebTestClient client;
    @AfterEach void cleanup() throws Exception { Files.deleteIfExists(Path.of("target/test-overrides.json")); }

    private WebTestClient.RequestHeadersSpec<?> authedGet() {
        return client.get().uri("/api/admin/config").header(HttpHeaders.AUTHORIZATION, "Bearer test-token");
    }
    @Test void getRequiresToken() { client.get().uri("/api/admin/config").exchange().expectStatus().isUnauthorized(); }
    @Test void getReturnsSnapshotWithVersion() {
        authedGet().exchange().expectStatus().isOk().expectBody(ConfigSnapshotDto.class).value(s -> assertThat(s.version()).isGreaterThanOrEqualTo(0));
    }
    @Test void putRoutingTogglesFlagAndBumpsVersion() {
        ConfigSnapshotDto before = authedGet().exchange().expectStatus().isOk().expectBody(ConfigSnapshotDto.class).returnResult().getResponseBody();
        ConfigSnapshotDto after = client.put()
                .uri(u -> u.path("/api/admin/config/routing").queryParam("expectedVersion", before.version()).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").bodyValue(new RoutingConfigDto(!before.routing().tkbPayEnabled()))
                .exchange().expectStatus().isOk().expectBody(ConfigSnapshotDto.class).returnResult().getResponseBody();
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.routing().tkbPayEnabled()).isEqualTo(!before.routing().tkbPayEnabled());
    }
    @Test void putWithStaleVersionReturns409() {
        client.put().uri(u -> u.path("/api/admin/config/routing").queryParam("expectedVersion", 99999).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").bodyValue(new RoutingConfigDto(true))
                .exchange().expectStatus().isEqualTo(409);
    }
}
