package ru.copperside.sbprouter.balancing;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-backend circuit-breaker state. Time is passed in by the caller (no Clock dependency) so the
 * class is trivially unit-testable. Thread-safe but <b>best-effort</b>: the counter is atomic and
 * the ban timestamp is volatile, but {@code recordFailure}/{@code recordSuccess} are not a single
 * atomic transaction across both fields. Under concurrent failures the ban may trigger one failure
 * early/late or the actuator may read a transient inconsistent snapshot — acceptable for an
 * in-memory, per-instance circuit-breaker (no correctness/safety impact on proxying).
 */
public class BackendHealth {

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long bannedUntilEpochMs = 0L;

    /** A backend is available once its ban (if any) has expired. */
    public boolean available(long nowMs) {
        return bannedUntilEpochMs <= nowMs;
    }

    /** Epoch-ms the current ban expires at (0 = never banned). Used to pick the half-open probe. */
    public long bannedUntil() {
        return bannedUntilEpochMs;
    }

    /** A completed HTTP response (any status) — clears failures and any ban. */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        bannedUntilEpochMs = 0L;
    }

    /** A transport error (timeout / connection failure). Bans the backend once the threshold is hit. */
    public void recordFailure(long nowMs, int threshold, long banMs) {
        int count = consecutiveFailures.incrementAndGet();
        if (count >= threshold) {
            bannedUntilEpochMs = nowMs + banMs;
            consecutiveFailures.set(0); // fresh N attempts after the ban expires
        }
    }
}
