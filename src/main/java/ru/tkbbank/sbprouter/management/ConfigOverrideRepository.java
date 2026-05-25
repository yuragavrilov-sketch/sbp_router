package ru.tkbbank.sbprouter.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Optional;

/** Persists the full config snapshot as JSON on the mounted config volume. */
@Component
public class ConfigOverrideRepository {
    private static final Logger log = LoggerFactory.getLogger(ConfigOverrideRepository.class);
    private final ObjectMapper objectMapper;
    private final Path path;

    public ConfigOverrideRepository(ObjectMapper objectMapper,
                                    @Value("${sbp-router.config.override-path:config/runtime-overrides.json}") String overridePath) {
        this.objectMapper = objectMapper;
        this.path = Path.of(overridePath);
    }

    public Optional<RouterConfigSnapshot> load() {
        if (!Files.exists(path)) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(Files.readAllBytes(path), RouterConfigSnapshot.class));
        } catch (IOException e) {
            log.error("Failed to read config override {}: {}", path, e.getMessage());
            throw new UncheckedIOException(e);
        }
    }

    /** Atomic write: temp file + move. */
    public void save(RouterConfigSnapshot snapshot) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "override-", ".tmp");
            Files.write(tmp, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot));
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Failed to write config override {}: {}", path, e.getMessage());
            throw new UncheckedIOException(e);
        }
    }
}
