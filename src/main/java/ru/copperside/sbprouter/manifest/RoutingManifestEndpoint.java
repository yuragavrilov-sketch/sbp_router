package ru.copperside.sbprouter.manifest;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Actuator endpoint {@code routing-manifest}:
 *   GET  /actuator/routing-manifest  → current live-config status
 *   POST /actuator/routing-manifest  → force an immediate poll, then return status
 * Active only when sbp-router.manifest.enabled=true (polling is on).
 */
@Component
@Endpoint(id = "routing-manifest")
@ConditionalOnProperty(prefix = "sbp-router.manifest", name = "enabled", havingValue = "true")
public class RoutingManifestEndpoint {

    private final ManifestPoller poller;
    private final RoutingConfigHolder holder;
    private final ManifestStatus status;

    public RoutingManifestEndpoint(ManifestPoller poller, RoutingConfigHolder holder, ManifestStatus status) {
        this.poller = poller;
        this.holder = holder;
        this.status = status;
    }

    @ReadOperation
    public Map<String, Object> status() {
        return view();
    }

    @WriteOperation
    public Map<String, Object> refresh() {
        poller.poll();
        return view();
    }

    private Map<String, Object> view() {
        RoutingConfigSnapshot current = holder.current();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", current.version() == null ? "static" : "manifest");
        m.put("version", current.version());
        m.put("checksum", current.checksum());
        m.put("lastFetchAt", status.lastFetchAt());
        m.put("lastOutcome", status.lastOutcome());
        return m;
    }
}
