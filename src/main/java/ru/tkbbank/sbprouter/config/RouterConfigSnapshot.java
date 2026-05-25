package ru.tkbbank.sbprouter.config;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable slice of the managed sbp-router configuration. Domain objects are
 * treated as effectively immutable: ConfigService always supplies fresh instances.
 */
public record RouterConfigSnapshot(
        SbpRouterProperties.Routing routing,
        SbpRouterProperties.Terminals terminals,
        Map<String, SbpRouterProperties.UpstreamConfig> upstreams,
        Map<String, SbpRouterProperties.ExtractionRuleSet> extractionRules,
        long version,
        Instant updatedAt
) {
    public static Builder builder() { return new Builder(); }

    public static Builder builder(RouterConfigSnapshot base) {
        return new Builder()
                .routing(base.routing()).terminals(base.terminals())
                .upstreams(base.upstreams()).extractionRules(base.extractionRules())
                .version(base.version());
    }

    public static RouterConfigSnapshot fromProperties(SbpRouterProperties props) {
        return builder()
                .routing(props.getRouting() != null ? props.getRouting() : new SbpRouterProperties.Routing())
                .terminals(props.getTerminals() != null ? props.getTerminals() : new SbpRouterProperties.Terminals())
                .upstreams(props.getUpstreams() != null ? props.getUpstreams() : Map.of())
                .extractionRules(props.getExtractionRules() != null ? props.getExtractionRules() : Map.of())
                .version(0).build();
    }

    public static final class Builder {
        private SbpRouterProperties.Routing routing = new SbpRouterProperties.Routing();
        private SbpRouterProperties.Terminals terminals = new SbpRouterProperties.Terminals();
        private Map<String, SbpRouterProperties.UpstreamConfig> upstreams = Map.of();
        private Map<String, SbpRouterProperties.ExtractionRuleSet> extractionRules = Map.of();
        private long version = 0;

        public Builder routing(SbpRouterProperties.Routing v) { this.routing = v; return this; }
        public Builder terminals(SbpRouterProperties.Terminals v) { this.terminals = v; return this; }
        public Builder upstreams(Map<String, SbpRouterProperties.UpstreamConfig> v) { this.upstreams = v != null ? Map.copyOf(v) : Map.of(); return this; }
        public Builder extractionRules(Map<String, SbpRouterProperties.ExtractionRuleSet> v) { this.extractionRules = v != null ? Map.copyOf(v) : Map.of(); return this; }
        public Builder version(long v) { this.version = v; return this; }

        public RouterConfigSnapshot build() {
            return new RouterConfigSnapshot(routing, terminals, upstreams, extractionRules, version, Instant.now());
        }
    }
}
