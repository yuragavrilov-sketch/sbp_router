package ru.copperside.sbprouter.balancing;

import java.time.Duration;
import java.util.Set;

/** AuthPay route: when enabled, ReqAuthPay whose SbpOperation is in {@code sbpOperations} (or any
 *  ReqAuthPay when the set is empty) is forwarded to {@code pool} (round-robin + failover + CB),
 *  fail-closed. */
public record AuthPayRoute(boolean enabled, BackendGroup pool, Duration timeout, Set<String> sbpOperations) {
    public static final AuthPayRoute DISABLED = new AuthPayRoute(false, null, null, Set.of());
}
