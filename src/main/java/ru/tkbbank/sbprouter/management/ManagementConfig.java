package ru.tkbbank.sbprouter.management;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;

@Configuration
public class ManagementConfig {
    private static final Logger log = LoggerFactory.getLogger(ManagementConfig.class);

    @Bean
    public ConfigStore configStore(SbpRouterProperties properties, ConfigOverrideRepository overrideRepository) {
        RouterConfigSnapshot initial = overrideRepository.load()
                .map(s -> { log.info("Loaded runtime config override (version={})", s.version()); return s; })
                .orElseGet(() -> { log.info("No runtime override, using application.yml baseline"); return RouterConfigSnapshot.fromProperties(properties); });
        return new ConfigStore(initial);
    }
}
