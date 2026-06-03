package ru.copperside.sbprouter.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
public class StartupDiagnostics implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupDiagnostics.class);

    private final Environment environment;
    private final SbpRouterProperties routerProperties;

    public StartupDiagnostics(Environment environment,
                              SbpRouterProperties routerProperties) {
        this.environment = environment;
        this.routerProperties = routerProperties;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        logConfiguration();
    }

    void logConfiguration() {
        log.info(
                "Startup configuration: application={}, environment={}, profiles={}, serverPort={}, "
                        + "configImports={}, configServerEnabled={}, vaultEnabled={}, upstreams={}",
                property("spring.application.name", "unknown"),
                property("pay.environment", "unknown"),
                profiles(),
                property("server.port", "default"),
                configImports(),
                property("spring.cloud.config.enabled", "false"),
                property("spring.cloud.vault.enabled", "false"),
                upstreamHosts()
        );
        logPropertySources();
        logExternalConfigState();
    }

    /** Lists the property sources in priority order and whether Config Server / Vault sources loaded. */
    private void logPropertySources() {
        if (!(environment instanceof ConfigurableEnvironment ce)) {
            return;
        }
        String names = ce.getPropertySources().stream()
                .map(PropertySource::getName)
                .collect(Collectors.joining(" | "));
        boolean configServerLoaded = ce.getPropertySources().stream()
                .map(ps -> ps.getName().toLowerCase())
                .anyMatch(n -> n.contains("configserver") || n.contains("configclient"));
        boolean vaultLoaded = ce.getPropertySources().stream()
                .map(ps -> ps.getName().toLowerCase())
                .anyMatch(n -> n.contains("vault"));
        log.info("Startup property sources (priority order): {}", names);
        log.info("Startup external sources: configServerLoaded={}, vaultLoaded={}",
                configServerLoaded, vaultLoaded);
    }

    /**
     * Logs whether the two secrets resolved and from which property source. Presence only — no
     * value, no username, no length, no Vault wiring (uri/role/path are known from the deployment
     * config; logging them would over-expose to log sinks).
     */
    private void logExternalConfigState() {
        log.info("Startup config-client password: {} (source={})",
                masked(environment.getProperty("spring.cloud.config.password")),
                sourceOf("spring.cloud.config.password"));
        log.info("Startup secret resolution: sbp-router.manifest.admin-key={} (source={})",
                masked(environment.getProperty("sbp-router.manifest.admin-key")),
                sourceOf("sbp-router.manifest.admin-key"));
    }

    /** Presence only — never the value or any secret-derived detail (e.g. length). */
    private static String masked(String value) {
        return (value == null || value.isBlank()) ? "NOT-SET" : "SET";
    }

    private String sourceOf(String key) {
        if (environment instanceof ConfigurableEnvironment ce) {
            for (PropertySource<?> ps : ce.getPropertySources()) {
                if (ps.containsProperty(key)) {
                    return ps.getName();
                }
            }
        }
        return "(none)";
    }

    private String profiles() {
        String activeProperty = environment.getProperty("spring.profiles.active");
        if (activeProperty != null && !activeProperty.isBlank()) {
            return activeProperty;
        }

        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length > 0) {
            return String.join(",", activeProfiles);
        }

        return String.join(",", environment.getDefaultProfiles());
    }

    private String configImports() {
        String directImport = environment.getProperty("spring.config.import");
        if (directImport != null && !directImport.isBlank()) {
            return directImport;
        }

        String indexedImports = IntStream.range(0, 10)
                .mapToObj(index -> environment.getProperty("spring.config.import[" + index + "]"))
                .takeWhile(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(","));

        return indexedImports.isBlank() ? "not-configured" : indexedImports;
    }

    private String property(String name, String defaultValue) {
        return environment.getProperty(name, defaultValue);
    }

    private String upstreamHosts() {
        Map<String, SbpRouterProperties.UpstreamConfig> upstreams = routerProperties.getUpstreams();
        if (upstreams == null || upstreams.isEmpty()) {
            return "none";
        }
        return upstreams.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + host(entry.getValue().getUrl()))
                .sorted()
                .collect(Collectors.joining(","));
    }

    private static String host(String url) {
        if (url == null || url.isBlank()) {
            return "not-configured";
        }
        try {
            String authority = java.net.URI.create(url).getAuthority();
            return authority != null ? authority : url;
        } catch (IllegalArgumentException ex) {
            return "invalid-url";
        }
    }
}
