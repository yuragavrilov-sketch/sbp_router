package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import ru.tkbbank.sbprouter.management.dto.StatusDto;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@TestPropertySource(properties = "sbp-router.admin.token=test-token")
class AdminMonitoringControllerIntegrationTest {
    @Autowired WebTestClient client;
    @Test void requestsRequireToken() { client.get().uri("/api/admin/requests").exchange().expectStatus().isUnauthorized(); }
    @Test void statusReturnsSnapshotAndHistoryInfo() {
        client.get().uri("/api/admin/status").header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk().expectBody(StatusDto.class).value(s -> {
                    assertThat(s.up()).isTrue();
                    assertThat(s.historyCapacity()).isGreaterThan(0);
                    assertThat(s.configVersion()).isGreaterThanOrEqualTo(0);
                });
    }
    @Test void requestsReturnsListWithToken() {
        client.get().uri("/api/admin/requests?limit=5").header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk().expectBodyList(Object.class);
    }
}
