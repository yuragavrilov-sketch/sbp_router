package ru.copperside.sbprouter.manifest;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Exposes the live routing-manifest version as a Micrometer gauge
 * ({@code sbp_router_manifest_version}). Reports 0 while on the static baseline
 * (snapshot version == null). Not gated — works in static mode too.
 */
@Component
public class ManifestMetrics {

    public ManifestMetrics(MeterRegistry registry, RoutingConfigHolder holder) {
        Gauge.builder("sbp_router_manifest_version", holder,
                        h -> {
                            Integer v = h.current().version();
                            return v == null ? 0d : v.doubleValue();
                        })
                .description("Live SBP routing manifest version (0 = static baseline)")
                .register(registry);
    }
}
