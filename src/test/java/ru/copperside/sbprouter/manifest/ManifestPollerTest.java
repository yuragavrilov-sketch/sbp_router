package ru.copperside.sbprouter.manifest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManifestPollerTest {

    private ManifestClient client;
    private RoutingConfigHolder holder;
    private ManifestPoller poller;

    private ManifestDtos.ManifestDto manifest(int version, String checksum, boolean tkbEnabled) {
        var payload = new ManifestDtos.ManifestPayload(
                Map.of(),
                new ManifestDtos.TerminalsDto("rcvTspId", "terminalName", "Pay", List.of()),
                Map.of("tkb-pay-enabled", String.valueOf(tkbEnabled)),
                Map.of("infosrv", new ManifestDtos.UpstreamDto("http://u", 1000,
                        new ManifestDtos.RetryDto(1, 100))));
        return new ManifestDtos.ManifestDto(version, checksum, payload);
    }

    @BeforeEach
    void setUp() {
        client = mock(ManifestClient.class);
        SbpRouterProperties p = new SbpRouterProperties();
        SbpRouterProperties.Routing r = new SbpRouterProperties.Routing();
        r.setTkbPayEnabled(false);
        p.setRouting(r);
        p.setUpstreams(Map.of());
        p.setExtractionRules(Map.of());
        holder = new RoutingConfigHolder(p);
        poller = new ManifestPoller(client, new ManifestAdapter(), holder);
    }

    @Test
    void appliesChangedManifest() {
        when(client.latest()).thenReturn(Optional.of(manifest(3, "sha256:new", true)));

        poller.poll();

        assertThat(holder.getRouting().isTkbPayEnabled()).isTrue();
        assertThat(holder.current().version()).isEqualTo(3);
    }

    @Test
    void skipsWhenVersionAndChecksumUnchanged() {
        when(client.latest()).thenReturn(Optional.of(manifest(3, "sha256:same", true)));
        poller.poll();
        when(client.latest()).thenReturn(Optional.of(manifest(3, "sha256:same", false)));
        poller.poll();
        assertThat(holder.getRouting().isTkbPayEnabled()).isTrue();
    }

    @Test
    void keepsCurrentWhenFetchEmpty() {
        when(client.latest()).thenReturn(Optional.empty());
        poller.poll();
        assertThat(holder.getRouting().isTkbPayEnabled()).isFalse();
        assertThat(holder.current().version()).isNull();
    }

    @Test
    void keepsCurrentWhenManifestInvalid() {
        var badPayload = new ManifestDtos.ManifestPayload(
                Map.of(),
                new ManifestDtos.TerminalsDto("rcvTspId", "terminalName", "Pay", List.of()),
                Map.of("tkb-pay-enabled", "true"),
                Map.of());
        when(client.latest()).thenReturn(Optional.of(new ManifestDtos.ManifestDto(9, "sha256:bad", badPayload)));

        poller.poll();

        assertThat(holder.getRouting().isTkbPayEnabled()).isFalse();
        assertThat(holder.current().version()).isNull();
    }
}
