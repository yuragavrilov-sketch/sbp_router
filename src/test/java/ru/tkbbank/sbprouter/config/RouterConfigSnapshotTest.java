package ru.tkbbank.sbprouter.config;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class RouterConfigSnapshotTest {
    @Test void builderFillsSaneDefaults() {
        var snap = RouterConfigSnapshot.builder().build();
        assertThat(snap.routing()).isNotNull();
        assertThat(snap.routing().isTkbPayEnabled()).isFalse();
        assertThat(snap.terminals()).isNotNull();
        assertThat(snap.upstreams()).isEmpty();
        assertThat(snap.extractionRules()).isEmpty();
        assertThat(snap.version()).isZero();
        assertThat(snap.updatedAt()).isNotNull();
    }
    @Test void fromPropertiesCopiesManagedDomains() {
        var props = new SbpRouterProperties();
        var routing = new SbpRouterProperties.Routing(); routing.setTkbPayEnabled(true);
        props.setRouting(routing);
        props.setUpstreams(Map.of("infosrv", new SbpRouterProperties.UpstreamConfig()));
        var snap = RouterConfigSnapshot.fromProperties(props);
        assertThat(snap.routing().isTkbPayEnabled()).isTrue();
        assertThat(snap.upstreams()).containsKey("infosrv");
        assertThat(snap.version()).isZero();
    }
    @Test void builderCopyKeepsUnchangedDomainsAndBumpsVersion() {
        var base = RouterConfigSnapshot.builder().version(5).build();
        var routing = new SbpRouterProperties.Routing(); routing.setTkbPayEnabled(true);
        var next = RouterConfigSnapshot.builder(base).routing(routing).version(6).build();
        assertThat(next.version()).isEqualTo(6);
        assertThat(next.routing().isTkbPayEnabled()).isTrue();
    }
}
