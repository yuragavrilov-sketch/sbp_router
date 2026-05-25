package ru.tkbbank.sbprouter.management.dto;

public record UpstreamDto(String url, Long timeoutMillis, Integer maxAttempts, Long backoffMillis) {}
