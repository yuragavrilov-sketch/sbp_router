package ru.copperside.sbprouter.manifest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Wire DTOs for the sbp-router-management latest-manifest response.
 * Envelope: { "data": { version, checksum, payload: { extractionRules, terminals, routing, upstreams } } }.
 * Unknown properties are ignored so additive contract changes don't break the router.
 */
public final class ManifestDtos {
    private ManifestDtos() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ManifestEnvelope(ManifestDto data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ManifestDto(Integer version, String checksum, ManifestPayload payload) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ManifestPayload(
            Map<String, ExtractionRuleDto> extractionRules,
            TerminalsDto terminals,
            Map<String, String> routing,
            Map<String, UpstreamDto> upstreams) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractionRuleDto(List<FieldBindingDto> routingFields, List<FieldBindingDto> extraFields) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FieldBindingDto(String name, String parent, String key, String path) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TerminalsDto(String c2bFieldName, String b2cFieldName, String tkbPayPrefix, List<String> tkbPayList) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpstreamDto(String url, Integer timeoutMs, RetryDto retry) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RetryDto(Integer maxAttempts, Integer backoffMs) {}
}
