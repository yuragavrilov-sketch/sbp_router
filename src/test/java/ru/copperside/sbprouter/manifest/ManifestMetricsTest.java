package ru.copperside.sbprouter.manifest;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestMetricsTest {

    private RoutingConfigHolder staticHolder() {
        SbpRouterProperties p = new SbpRouterProperties();
        p.setRouting(new SbpRouterProperties.Routing());
        p.setUpstreams(Map.of());
        p.setExtractionRules(Map.of());
        return new RoutingConfigHolder(p);
    }

    @Test
    void gaugeReportsZeroForStaticBaselineThenVersionAfterApply() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RoutingConfigHolder holder = staticHolder();
        new ManifestMetrics(registry, holder);

        var gauge = registry.find("sbp_router_manifest_version").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(0d);

        holder.apply(new RoutingConfigSnapshot(Map.of(), new SbpRouterProperties.Terminals(),
                new SbpRouterProperties.Routing(), Map.of(), 4, "sha256:x"));
        assertThat(gauge.value()).isEqualTo(4d);
    }
}
