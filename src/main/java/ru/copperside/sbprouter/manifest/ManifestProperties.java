package ru.copperside.sbprouter.manifest;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for consuming the routing manifest from sbp-router-management.
 * Bound from `sbp-router.manifest.*`. Picked up by @ConfigurationPropertiesScan.
 */
@ConfigurationProperties(prefix = "sbp-router.manifest")
public record ManifestProperties(boolean enabled, String baseUrl, String adminKey) {
}
