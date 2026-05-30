package ru.copperside.sbprouter.manifest;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Surfaces the live routing-config source under actuator {@code info.routingManifest}:
 * source (static|manifest), version, checksum, lastFetchAt, lastOutcome.
 */
@Component
public class ManifestInfoContributor implements InfoContributor {

    private final RoutingConfigHolder holder;
    private final ManifestStatus status;

    public ManifestInfoContributor(RoutingConfigHolder holder, ManifestStatus status) {
        this.holder = holder;
        this.status = status;
    }

    @Override
    public void contribute(Info.Builder builder) {
        RoutingConfigSnapshot current = holder.current();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("source", current.version() == null ? "static" : "manifest");
        details.put("version", current.version());
        details.put("checksum", current.checksum());
        details.put("lastFetchAt", status.lastFetchAt());
        details.put("lastOutcome", status.lastOutcome());
        builder.withDetail("routingManifest", details);
    }
}
