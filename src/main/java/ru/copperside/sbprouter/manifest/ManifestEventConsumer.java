package ru.copperside.sbprouter.manifest;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Consumes the "manifest published" trigger event and forces an immediate poll
 * (fetch-latest + apply). The event body is logged but otherwise ignored — the poller
 * always fetches the current latest, so duplicate/out-of-order events converge safely.
 * Active only when sbp-router.manifest.enabled=true.
 */
@Component
@ConditionalOnProperty(prefix = "sbp-router.manifest", name = "enabled", havingValue = "true")
public class ManifestEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ManifestEventConsumer.class);

    private final ManifestPoller poller;

    public ManifestEventConsumer(ManifestPoller poller) {
        this.poller = poller;
    }

    @KafkaListener(topics = "${sbp-router.manifest.event-topic:sbp-router-manifest}",
            groupId = "sbp-router",
            containerFactory = "manifestEventListenerContainerFactory")
    public void onManifestPublished(ConsumerRecord<String, byte[]> record) {
        String body = record.value() == null ? "" : new String(record.value(), StandardCharsets.UTF_8);
        log.info("manifest published event received (key={}, body={}); polling latest", record.key(), body);
        poller.poll();
    }
}
