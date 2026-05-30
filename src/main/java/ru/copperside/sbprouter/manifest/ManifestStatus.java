package ru.copperside.sbprouter.manifest;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Tracks the outcome of the most recent manifest poll for observability.
 * Always present (even when polling is disabled — then it simply stays empty).
 */
@Component
public class ManifestStatus {

    private volatile Instant lastFetchAt;
    private volatile String lastOutcome;

    public void record(String outcome) {
        this.lastOutcome = outcome;
        this.lastFetchAt = Instant.now();
    }

    public Instant lastFetchAt() {
        return lastFetchAt;
    }

    public String lastOutcome() {
        return lastOutcome;
    }
}
