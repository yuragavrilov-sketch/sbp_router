package ru.copperside.sbprouter.manifest;

import org.junit.jupiter.api.Test;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingConfigHolderTest {

    private SbpRouterProperties staticProps() {
        SbpRouterProperties p = new SbpRouterProperties();
        SbpRouterProperties.Routing r = new SbpRouterProperties.Routing();
        r.setTkbPayEnabled(false);
        p.setRouting(r);
        p.setUpstreams(Map.of());
        p.setExtractionRules(Map.of());
        return p;
    }

    @Test
    void seedsFromStaticProperties() {
        RoutingConfigHolder holder = new RoutingConfigHolder(staticProps());
        assertThat(holder.getRouting().isTkbPayEnabled()).isFalse();
        assertThat(holder.current().version()).isNull();
    }

    @Test
    void applySwapsSnapshotAtomically() {
        RoutingConfigHolder holder = new RoutingConfigHolder(staticProps());

        SbpRouterProperties.Routing newRouting = new SbpRouterProperties.Routing();
        newRouting.setTkbPayEnabled(true);
        RoutingConfigSnapshot next = new RoutingConfigSnapshot(
                Map.of(), new SbpRouterProperties.Terminals(), newRouting, Map.of(), 7, "sha256:abc");

        holder.apply(next);

        assertThat(holder.getRouting().isTkbPayEnabled()).isTrue();
        assertThat(holder.current().version()).isEqualTo(7);
        assertThat(holder.current().checksum()).isEqualTo("sha256:abc");
    }
}
