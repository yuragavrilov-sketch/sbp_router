package ru.copperside.sbprouter.manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Optional;

/**
 * Fetches the latest compiled routing manifest from sbp-router-management's internal API,
 * authenticating with the shared X-Internal-Admin-Key. Never throws: any transport/HTTP
 * error yields an empty Optional so the poller can keep the current snapshot.
 * Active only when sbp-router.manifest.enabled=true.
 */
@Component
@ConditionalOnProperty(prefix = "sbp-router.manifest", name = "enabled", havingValue = "true")
public class ManifestClient {

    private static final Logger log = LoggerFactory.getLogger(ManifestClient.class);
    private static final String PATH = "/internal/v1/sbp-router-management/routing-manifests/latest";
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final ManifestProperties props;

    public ManifestClient(WebClient proxyWebClient, ManifestProperties props) {
        this.webClient = proxyWebClient;
        this.props = props;
    }

    public Optional<ManifestDtos.ManifestDto> latest() {
        try {
            ManifestDtos.ManifestEnvelope envelope = webClient.get()
                    .uri(props.baseUrl() + PATH)
                    .header("X-Internal-Admin-Key", props.adminKey() == null ? "" : props.adminKey())
                    .retrieve()
                    .bodyToMono(ManifestDtos.ManifestEnvelope.class)
                    .timeout(FETCH_TIMEOUT)
                    .block();
            return Optional.ofNullable(envelope).map(ManifestDtos.ManifestEnvelope::data);
        } catch (Exception e) {
            log.warn("routing manifest fetch failed: {}", e.toString());
            return Optional.empty();
        }
    }
}
