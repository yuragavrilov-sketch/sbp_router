package ru.copperside.sbprouter.balancing;

import java.time.Duration;

/**
 * The optional AuthPay route: when {@code enabled}, ReqAuthPay messages are forwarded to {@code pool}
 * (round-robin + failover + circuit-breaking, like any group), with an optional per-attempt timeout
 * override. {@link #DISABLED} means no branching (flat proxy).
 */
public record AuthPayRoute(boolean enabled, BackendGroup pool, Duration timeout) {

    public static final AuthPayRoute DISABLED = new AuthPayRoute(false, null, null);
}
