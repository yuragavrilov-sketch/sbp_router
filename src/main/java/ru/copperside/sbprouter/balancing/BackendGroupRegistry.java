package ru.copperside.sbprouter.balancing;

import org.springframework.stereotype.Component;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the configured backend groups and the currently-active group. Built and validated once at
 * startup (fail-fast on misconfiguration); the active group can be switched at runtime.
 */
@Component
public class BackendGroupRegistry {

    private final Map<String, BackendGroup> groups;
    private final AtomicReference<String> activeGroupName;

    public BackendGroupRegistry(SbpRouterProperties properties) {
        this.groups = build(properties);
        String active = properties.getActiveGroup();
        if (active == null || !groups.containsKey(active)) {
            throw new IllegalStateException(
                    "sbp-router.active-group '" + active + "' is not one of the configured groups " + groups.keySet());
        }
        this.activeGroupName = new AtomicReference<>(active);
    }

    private static Map<String, BackendGroup> build(SbpRouterProperties properties) {
        Map<String, SbpRouterProperties.Group> configured = properties.getGroups();
        if (configured == null || configured.isEmpty()) {
            throw new IllegalStateException("sbp-router.groups must define at least one group");
        }
        Map<String, BackendGroup> result = new LinkedHashMap<>();
        configured.forEach((name, group) -> {
            List<String> urls = group.getBackends();
            if (urls == null || urls.isEmpty()) {
                throw new IllegalStateException("sbp-router group '" + name + "' must have at least one backend");
            }
            List<Backend> backends = new ArrayList<>(urls.size());
            for (String url : urls) {
                if (url == null || url.isBlank()) {
                    throw new IllegalStateException("sbp-router group '" + name + "' has a blank backend url");
                }
                backends.add(new Backend(url, new BackendHealth()));
            }
            result.put(name, new BackendGroup(name, backends));
        });
        return Map.copyOf(result);
    }

    public BackendGroup activeGroup() {
        return groups.get(activeGroupName.get());
    }

    public String activeGroupName() {
        return activeGroupName.get();
    }

    public void setActiveGroup(String name) {
        if (!groups.containsKey(name)) {
            throw new IllegalArgumentException("unknown group: " + name);
        }
        activeGroupName.set(name);
    }

    public Map<String, BackendGroup> groups() {
        return groups;
    }
}
