package ru.copperside.sbprouter.manifest;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for consuming the routing manifest from sbp-router-management.
 * Bound from `sbp-router.manifest.*`. Picked up by @ConfigurationPropertiesScan.
 *
 * Note: `sbp-router.manifest.poll-interval` and `initial-delay` are intentionally NOT
 * fields here — they are consumed directly by ManifestPoller's @Scheduled placeholders
 * (relaxed binding ignores the unmapped keys). Keep them in application.yml.
 */
@ConfigurationProperties(prefix = "sbp-router.manifest")
public record ManifestProperties(boolean enabled, String baseUrl, String adminKey) {
}
