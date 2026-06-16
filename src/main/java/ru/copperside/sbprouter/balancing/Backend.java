package ru.copperside.sbprouter.balancing;

/** One backend endpoint plus its mutable circuit-breaker health. */
public record Backend(String url, BackendHealth health) {
}
