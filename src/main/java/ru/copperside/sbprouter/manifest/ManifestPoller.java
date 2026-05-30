package ru.copperside.sbprouter.manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Periodically fetches the latest routing manifest and applies it to the RoutingConfigHolder
 * when version/checksum changed. Any fetch/validation failure leaves the current snapshot
 * intact (last-known-good). Active only when sbp-router.manifest.enabled=true.
 */
@Component
@ConditionalOnProperty(prefix = "sbp-router.manifest", name = "enabled", havingValue = "true")
public class ManifestPoller {

    private static final Logger log = LoggerFactory.getLogger(ManifestPoller.class);

    private final ManifestClient client;
    private final ManifestAdapter adapter;
    private final RoutingConfigHolder holder;
    private final ManifestStatus status;

    public ManifestPoller(ManifestClient client, ManifestAdapter adapter,
                          RoutingConfigHolder holder, ManifestStatus status) {
        this.client = client;
        this.adapter = adapter;
        this.holder = holder;
        this.status = status;
    }

    @Scheduled(fixedDelayString = "${sbp-router.manifest.poll-interval:30s}",
            initialDelayString = "${sbp-router.manifest.initial-delay:2s}")
    public void poll() {
        Optional<ManifestDtos.ManifestDto> latest = client.latest();
        if (latest.isEmpty()) {
            log.debug("routing manifest unavailable; keeping current snapshot");
            status.record("UNAVAILABLE");
            return;
        }
        ManifestDtos.ManifestDto dto = latest.get();
        RoutingConfigSnapshot current = holder.current();
        if (Objects.equals(current.version(), dto.version())
                && Objects.equals(current.checksum(), dto.checksum())) {
            status.record("UNCHANGED");
            return;
        }
        try {
            holder.apply(adapter.toSnapshot(dto));
            status.record("APPLIED v" + dto.version());
        } catch (Exception e) {
            log.warn("routing manifest rejected, keeping current snapshot: {}", e.toString());
            status.record("REJECTED: " + e.getMessage());
        }
    }
}
