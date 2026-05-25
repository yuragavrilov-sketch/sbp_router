package ru.tkbbank.sbprouter.history;

import java.time.Instant;

public record RequestRecord(Instant timestamp, String correlationId, String requestType, String terminal,
        String terminalOwner, String sbpOperType, String routeDecision, Integer upstreamStatusCode, long durationMs, String error) {}
