package ru.tkbbank.sbprouter.management.dto;

public record RequestRecordDto(String timestamp, String correlationId, String requestType, String terminal,
        String terminalOwner, String sbpOperType, String routeDecision, Integer upstreamStatusCode, long durationMs, String error) {}
