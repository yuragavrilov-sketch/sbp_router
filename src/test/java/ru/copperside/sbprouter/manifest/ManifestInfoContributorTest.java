package ru.copperside.sbprouter.manifest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestInfoContributorTest {

    private RoutingConfigHolder staticHolder() {
        SbpRouterProperties p = new SbpRouterProperties();
        p.setRouting(new SbpRouterProperties.Routing());
        p.setUpstreams(Map.of());
        p.setExtractionRules(Map.of());
        return new RoutingConfigHolder(p);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsStaticSourceForBaseline() {
        ManifestInfoContributor c = new ManifestInfoContributor(staticHolder(), new ManifestStatus());
        Info.Builder b = new Info.Builder();
        c.contribute(b);
        Map<String, Object> rm = (Map<String, Object>) b.build().getDetails().get("routingManifest");
        assertThat(rm.get("source")).isEqualTo("static");
        assertThat(rm.get("version")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsManifestSourceAndOutcomeAfterApply() {
        RoutingConfigHolder holder = staticHolder();
        ManifestStatus status = new ManifestStatus();
        holder.apply(new RoutingConfigSnapshot(Map.of(), new SbpRouterProperties.Terminals(),
                new SbpRouterProperties.Routing(), Map.of(), 7, "sha256:abc"));
        status.record("APPLIED v7");

        ManifestInfoContributor c = new ManifestInfoContributor(holder, status);
        Info.Builder b = new Info.Builder();
        c.contribute(b);
        Map<String, Object> rm = (Map<String, Object>) b.build().getDetails().get("routingManifest");

        assertThat(rm.get("source")).isEqualTo("manifest");
        assertThat(rm.get("version")).isEqualTo(7);
        assertThat(rm.get("checksum")).isEqualTo("sha256:abc");
        assertThat(rm.get("lastOutcome")).isEqualTo("APPLIED v7");
        assertThat(rm.get("lastFetchAt")).isNotNull();
    }
}
