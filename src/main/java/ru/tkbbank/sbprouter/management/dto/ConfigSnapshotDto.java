package ru.tkbbank.sbprouter.management.dto;

public record ConfigSnapshotDto(long version, String updatedAt, RoutingConfigDto routing, TerminalsConfigDto terminals,
        java.util.Map<String, UpstreamDto> upstreams, java.util.Map<String, ExtractionRuleSetDto> extractionRules) {}
