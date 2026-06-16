package ru.copperside.sbprouter.balancing;

import org.springframework.stereotype.Component;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the configured backend groups and the currently-active group. Built and validated once at
 * startup (fail-fast on misconfiguration); the entire group set can be replaced atomically at
 * runtime via {@link #replace(Map, String, long)} when a new routing config is applied.
 */
@Component
public class BackendGroupRegistry {

    private final AtomicReference<Map<String, BackendGroup>> groups;
    private final AtomicReference<String> activeGroupName;
    private final AtomicLong appliedVersion = new AtomicLong(0L);

    public BackendGroupRegistry(SbpRouterProperties properties) {
        Map<String, BackendGroup> built = build(properties);
        String active = properties.getActiveGroup();
        if (active == null || !built.containsKey(active)) {
            throw new IllegalStateException(
                    "sbp-router.active-group '" + active + "' is not one of the configured groups " + built.keySet());
        }
        this.groups = new AtomicReference<>(built);
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
        return groups.get().get(activeGroupName.get());
    }

    public String activeGroupName() {
        return activeGroupName.get();
    }

    public void setActiveGroup(String name) {
        if (!groups.get().containsKey(name)) {
            throw new IllegalArgumentException("unknown group: " + name);
        }
        activeGroupName.set(name);
    }

    public Map<String, BackendGroup> groups() {
        return groups.get();
    }

    public long appliedVersion() {
        return appliedVersion.get();
    }

    /**
     * Atomically swap the whole group set + active group (e.g. from a published routing config).
     * Fresh {@link BackendHealth} instances mean ban state resets on every reconfig.
     */
    public void replace(Map<String, BackendGroup> newGroups, String activeGroup, long version) {
        if (newGroups == null || newGroups.isEmpty() || !newGroups.containsKey(activeGroup)) {
            throw new IllegalArgumentException("invalid routing config: activeGroup '" + activeGroup
                    + "' not in " + (newGroups == null ? "null" : newGroups.keySet()));
        }
        this.groups.set(Map.copyOf(newGroups));
        this.activeGroupName.set(activeGroup);
        this.appliedVersion.set(version);
    }
}
