package ru.copperside.sbprouter.balancing;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoadBalancerTest {

    private static Clock fixed(long epochMs) {
        return Clock.fixed(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }

    private static BackendGroup group(Backend... b) {
        return new BackendGroup("g", List.of(b));
    }

    @Test
    void roundRobinOrdersHealthyStartingAtRotatingCursor() {
        Backend a = new Backend("http://a", new BackendHealth());
        Backend b = new Backend("http://b", new BackendHealth());
        BackendGroup g = group(a, b);
        LoadBalancer lb = new LoadBalancer(fixed(1_000L));

        assertThat(lb.selectCandidates(g)).containsExactly(a, b);
        assertThat(lb.selectCandidates(g)).containsExactly(b, a); // cursor advanced
    }

    @Test
    void skipsBannedBackends() {
        Backend a = new Backend("http://a", new BackendHealth());
        Backend b = new Backend("http://b", new BackendHealth());
        for (int i = 0; i < 3; i++) a.health().recordFailure(1_000L, 3, 5_000L); // a banned until 6000
        LoadBalancer lb = new LoadBalancer(fixed(2_000L));
        assertThat(lb.selectCandidates(group(a, b))).containsExactly(b);
    }

    @Test
    void halfOpenWhenAllBannedReturnsSoonestExpiring() {
        Backend a = new Backend("http://a", new BackendHealth());
        Backend b = new Backend("http://b", new BackendHealth());
        for (int i = 0; i < 3; i++) a.health().recordFailure(1_000L, 3, 10_000L); // a until 11000
        for (int i = 0; i < 3; i++) b.health().recordFailure(1_000L, 3, 5_000L);  // b until 6000 (sooner)
        LoadBalancer lb = new LoadBalancer(fixed(2_000L));
        assertThat(lb.selectCandidates(group(a, b))).containsExactly(b);
    }
}
