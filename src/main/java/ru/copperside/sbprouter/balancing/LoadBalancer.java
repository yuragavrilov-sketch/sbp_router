package ru.copperside.sbprouter.balancing;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Produces the ordered list of backends to try for one request: healthy backends in round-robin
 * order; or, when none are healthy, a single half-open probe (the banned backend whose ban expires
 * soonest) so the group never becomes a permanent black hole.
 */
@Component
public class LoadBalancer {

    private final Clock clock;

    public LoadBalancer(Clock clock) {
        this.clock = clock;
    }

    public List<Backend> selectCandidates(BackendGroup group) {
        long now = clock.millis();
        List<Backend> healthy = new ArrayList<>();
        for (Backend b : group.backends()) {
            if (b.health().available(now)) {
                healthy.add(b);
            }
        }
        if (!healthy.isEmpty()) {
            int start = group.nextStartIndex(healthy.size());
            List<Backend> ordered = new ArrayList<>(healthy.size());
            for (int i = 0; i < healthy.size(); i++) {
                ordered.add(healthy.get((start + i) % healthy.size()));
            }
            return ordered;
        }
        // Half-open: everything is banned — probe the one recovering soonest.
        Backend probe = group.backends().stream()
                .min(Comparator.comparingLong(b -> b.health().bannedUntil()))
                .orElseThrow();
        return List.of(probe);
    }
}
