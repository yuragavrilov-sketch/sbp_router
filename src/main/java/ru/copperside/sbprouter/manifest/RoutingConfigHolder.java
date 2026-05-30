package ru.copperside.sbprouter.manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the current routing configuration snapshot behind an AtomicReference so it
 * can be hot-swapped (from a published manifest) without restarting. Seeded at
 * construction from the static YAML-bound properties (the baseline / last-known-good).
 */
@Component
public class RoutingConfigHolder {

    private static final Logger log = LoggerFactory.getLogger(RoutingConfigHolder.class);

    private final AtomicReference<RoutingConfigSnapshot> ref;

    public RoutingConfigHolder(SbpRouterProperties properties) {
        this.ref = new AtomicReference<>(RoutingConfigSnapshot.fromStatic(properties));
    }

    public RoutingConfigSnapshot current() {
        return ref.get();
    }

    public void apply(RoutingConfigSnapshot next) {
        ref.set(next);
        log.info("routing config snapshot applied (version={}, checksum={})",
                next.version(), next.checksum());
    }

    public Map<String, SbpRouterProperties.ExtractionRuleSet> getExtractionRules() {
        return ref.get().extractionRules();
    }

    public SbpRouterProperties.Terminals getTerminals() {
        return ref.get().terminals();
    }

    public SbpRouterProperties.Routing getRouting() {
        return ref.get().routing();
    }

    public Map<String, SbpRouterProperties.UpstreamConfig> getUpstreams() {
        return ref.get().upstreams();
    }
}
