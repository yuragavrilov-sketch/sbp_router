package ru.copperside.sbprouter.manifest;

import org.junit.jupiter.api.Test;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RoutingManifestEndpointTest {

    private RoutingConfigHolder staticHolder() {
        SbpRouterProperties p = new SbpRouterProperties();
        p.setRouting(new SbpRouterProperties.Routing());
        p.setUpstreams(Map.of());
        p.setExtractionRules(Map.of());
        return new RoutingConfigHolder(p);
    }

    @Test
    void readReturnsCurrentStatus() {
        RoutingConfigHolder holder = staticHolder();
        ManifestStatus status = new ManifestStatus();
        ManifestPoller poller = mock(ManifestPoller.class);
        RoutingManifestEndpoint endpoint = new RoutingManifestEndpoint(poller, holder, status);

        Map<String, Object> view = endpoint.status();
        assertThat(view.get("source")).isEqualTo("static");
        assertThat(view.get("version")).isNull();
    }

    @Test
    void refreshTriggersPollAndReturnsStatus() {
        RoutingConfigHolder holder = staticHolder();
        ManifestStatus status = new ManifestStatus();
        ManifestPoller poller = mock(ManifestPoller.class);
        RoutingManifestEndpoint endpoint = new RoutingManifestEndpoint(poller, holder, status);

        Map<String, Object> view = endpoint.refresh();

        verify(poller).poll();
        assertThat(view).containsKey("source");
    }
}
