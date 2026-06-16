package ru.copperside.sbprouter.admin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.copperside.sbprouter.balancing.ActiveGroupPublisher;
import ru.copperside.sbprouter.balancing.BackendGroupRegistry;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.time.Clock;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/active-group")
public class AdminController {

    private final BackendGroupRegistry registry;
    private final ActiveGroupPublisher publisher;  // nullable
    private final SbpRouterProperties properties;
    private final Clock clock;

    public AdminController(BackendGroupRegistry registry,
                           ObjectProvider<ActiveGroupPublisher> publisherProvider,
                           SbpRouterProperties properties,
                           Clock clock) {
        this.registry = registry;
        this.publisher = publisherProvider.getIfAvailable();
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping
    public Map<String, Object> state() {
        long now = clock.millis();
        List<Map<String, Object>> groups = registry.groups().values().stream()
                .map(g -> Map.<String, Object>of(
                        "name", g.name(),
                        "backends", g.backends().stream()
                                .map(b -> Map.<String, Object>of(
                                        "url", b.url(),
                                        "banned", !b.health().available(now),
                                        "bannedUntil", b.health().bannedUntil()))
                                .toList()))
                .toList();
        return Map.of("activeGroup", registry.activeGroupName(), "groups", groups);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> switchGroup(@RequestBody Map<String, String> body) {
        String name = body == null ? null : body.get("name");
        if (name == null || !registry.groups().containsKey(name)) {
            return ResponseEntity.status(404).body(Map.of("error", "unknown group: " + name));
        }
        if (properties.getActiveGroupSync().isEnabled()) {
            try {
                publisher.publish(name);
            } catch (Exception e) {
                return ResponseEntity.status(503).body(Map.of("error", "broadcast failed: " + e.getMessage()));
            }
            return ResponseEntity.accepted().body(Map.of("status", "broadcasting", "name", name));
        }
        registry.setActiveGroup(name);
        return ResponseEntity.ok(Map.of("activeGroup", registry.activeGroupName()));
    }
}
