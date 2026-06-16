package ru.copperside.sbprouter.balancing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackendHealthTest {

    @Test
    void bannedAfterThresholdConsecutiveFailures() {
        BackendHealth h = new BackendHealth();
        long now = 1_000L;
        assertThat(h.available(now)).isTrue();
        h.recordFailure(now, 3, 5_000L);
        h.recordFailure(now, 3, 5_000L);
        assertThat(h.available(now)).isTrue();        // 2 < 3, not yet
        h.recordFailure(now, 3, 5_000L);              // 3rd -> ban
        assertThat(h.available(now)).isFalse();
        assertThat(h.bannedUntil()).isEqualTo(now + 5_000L);
    }

    @Test
    void availableAgainAfterBanExpires() {
        BackendHealth h = new BackendHealth();
        for (int i = 0; i < 3; i++) h.recordFailure(1_000L, 3, 5_000L);
        assertThat(h.available(5_999L)).isFalse();
        assertThat(h.available(6_000L)).isTrue();     // bannedUntil = 6000, available when now >= it
    }

    @Test
    void successResetsCounterAndClearsBan() {
        BackendHealth h = new BackendHealth();
        h.recordFailure(1_000L, 3, 5_000L);
        h.recordFailure(1_000L, 3, 5_000L);
        h.recordSuccess();                            // counter back to 0
        h.recordFailure(1_000L, 3, 5_000L);           // only 1 again
        assertThat(h.available(1_000L)).isTrue();
    }

    @Test
    void counterResetsAfterBanSoFreshNAttemptsGranted() {
        BackendHealth h = new BackendHealth();
        for (int i = 0; i < 3; i++) h.recordFailure(1_000L, 3, 5_000L);  // banned
        // one more failure after ban set: counter was reset to 0 -> now 1, ban timestamp unchanged
        h.recordFailure(2_000L, 3, 5_000L);
        assertThat(h.bannedUntil()).isEqualTo(6_000L);
    }
}
