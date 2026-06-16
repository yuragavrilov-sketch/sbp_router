package ru.copperside.sbprouter.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class AdminControllerTest {

    @Autowired
    private WebTestClient webClient;

    @Test
    void getActiveGroupReturnsState() {
        webClient.get()
                .uri("/admin/active-group")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.activeGroup").isEqualTo("default");
    }

    @Test
    void postKnownGroupSwitchesActiveGroup() {
        // activeGroupSync.enabled = false (default) → switches locally
        webClient.post()
                .uri("/admin/active-group")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"default\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.activeGroup").isEqualTo("default");
    }

    @Test
    void postUnknownGroupReturns404() {
        webClient.post()
                .uri("/admin/active-group")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"nonexistent\"}")
                .exchange()
                .expectStatus().isNotFound();
    }
}
