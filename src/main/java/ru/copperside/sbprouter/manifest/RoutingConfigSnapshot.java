package ru.copperside.sbprouter.manifest;

import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.util.Map;

/**
 * Immutable snapshot of the resolved routing configuration. Reuses the existing
 * SbpRouterProperties sub-types so the routing components read identical getters
 * whether the config came from static YAML or a published manifest.
 */
public record RoutingConfigSnapshot(
        Map<String, SbpRouterProperties.ExtractionRuleSet> extractionRules,
        SbpRouterProperties.Terminals terminals,
        SbpRouterProperties.Routing routing,
        Map<String, SbpRouterProperties.UpstreamConfig> upstreams,
        Integer version,
        String checksum) {

    /** Baseline snapshot from the statically-bound YAML properties (version/checksum null). */
    public static RoutingConfigSnapshot fromStatic(SbpRouterProperties p) {
        return new RoutingConfigSnapshot(
                p.getExtractionRules(), p.getTerminals(), p.getRouting(), p.getUpstreams(), null, null);
    }
}
