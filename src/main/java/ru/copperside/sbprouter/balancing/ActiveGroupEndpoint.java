package ru.copperside.sbprouter.balancing;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Operational endpoint to read the load-balancing state and switch the active backend group at
 * runtime (no restart). Exposed under the management context; protect it via management config /
 * network policy.
 */
@Component
@Endpoint(id = "activegroup")
public class ActiveGroupEndpoint {

    private final BackendGroupRegistry registry;
    private final Clock clock;

    public ActiveGroupEndpoint(BackendGroupRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    @ReadOperation
    public Map<String, Object> state() {
        long now = clock.millis();
        List<Map<String, Object>> groups = registry.groups().values().stream()
                .map(group -> Map.<String, Object>of(
                        "name", group.name(),
                        "backends", group.backends().stream()
                                .map(b -> Map.<String, Object>of(
                                        "url", b.url(),
                                        "banned", !b.health().available(now),
                                        "bannedUntil", b.health().bannedUntil()))
                                .toList()))
                .toList();
        return Map.of("activeGroup", registry.activeGroupName(), "groups", groups);
    }

    @WriteOperation
    public WebEndpointResponse<Map<String, Object>> selectGroup(String name) {
        if (name == null || !registry.groups().containsKey(name)) {
            return new WebEndpointResponse<>(Map.of("error", "unknown group: " + name),
                    WebEndpointResponse.STATUS_NOT_FOUND);
        }
        registry.setActiveGroup(name);
        return new WebEndpointResponse<>(Map.of("activeGroup", registry.activeGroupName()),
                WebEndpointResponse.STATUS_OK);
    }
}
