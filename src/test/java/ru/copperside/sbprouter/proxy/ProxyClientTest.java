package ru.copperside.sbprouter.proxy;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;
import ru.copperside.sbprouter.balancing.BackendGroupRegistry;
import ru.copperside.sbprouter.balancing.LoadBalancer;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class ProxyClientTest {

    WireMockServer good = new WireMockServer(0);
    WireMockServer slow = new WireMockServer(0);

    @BeforeEach
    void start() { good.start(); slow.start(); }

    @AfterEach
    void stop() { good.stop(); slow.stop(); }

    private ProxyClient proxy(SbpRouterProperties props) {
        HttpClient hc = HttpClient.create().responseTimeout(Duration.ofSeconds(5));
        WebClient wc = WebClient.builder().clientConnector(new ReactorClientHttpConnector(hc)).build();
        BackendGroupRegistry registry = new BackendGroupRegistry(props);
        LoadBalancer lb = new LoadBalancer(Clock.systemUTC());
        return new ProxyClient(wc, props, registry, lb, Clock.systemUTC());
    }

    private static SbpRouterProperties props(List<String> backends, int k, int threshold) {
        SbpRouterProperties p = new SbpRouterProperties();
        p.setActiveGroup("default");
        SbpRouterProperties.Group g = new SbpRouterProperties.Group();
        g.setBackends(backends);
        p.getGroups().put("default", g);
        p.setTimeout(Duration.ofMillis(300));
        p.getFailover().setMaxAttempts(k);
        p.getCircuitBreaker().setFailureThreshold(threshold);
        return p;
    }

    @Test
    void failsOverToHealthyBackendOnTimeout() {
        slow.stubFor(post(urlEqualTo("/api/gcsvc")).willReturn(aResponse().withFixedDelay(2000).withStatus(200)));
        good.stubFor(post(urlEqualTo("/api/gcsvc")).willReturn(aResponse().withStatus(200).withBody("<ok/>")));
        // order is round-robin; put slow first so the first attempt times out
        SbpRouterProperties p = props(List.of(slow.baseUrl() + "/api/gcsvc", good.baseUrl() + "/api/gcsvc"), 2, 3);
        ProxyClient proxy = proxy(p);

        StepVerifier.create(proxy.forward("<x/>".getBytes(), new HttpHeaders()))
                .assertNext(r -> {
                    assertThat(r.status().value()).isEqualTo(200);
                    assertThat(new String(r.body())).isEqualTo("<ok/>");
                })
                .verifyComplete();
        good.verify(1, postRequestedFor(urlEqualTo("/api/gcsvc")));
    }

    @Test
    void errorsWhenAllAttemptsFailWithinKCap() {
        slow.stubFor(post(urlEqualTo("/api/gcsvc")).willReturn(aResponse().withFixedDelay(2000).withStatus(200)));
        SbpRouterProperties p = props(List.of(slow.baseUrl() + "/api/gcsvc", slow.baseUrl() + "/api/gcsvc"), 2, 3);
        ProxyClient proxy = proxy(p);

        StepVerifier.create(proxy.forward("<x/>".getBytes(), new HttpHeaders()))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(ru.copperside.sbprouter.balancing.AllBackendsFailedException.class);
                    assertThat(((ru.copperside.sbprouter.balancing.AllBackendsFailedException) ex).lastWasTimeout()).isTrue();
                })
                .verify();
    }
}
