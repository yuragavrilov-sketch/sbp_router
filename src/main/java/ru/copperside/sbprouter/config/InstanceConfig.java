package ru.copperside.sbprouter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Instant;
import java.util.UUID;

@Configuration
public class InstanceConfig {
    @Bean
    public String instanceId() { return UUID.randomUUID().toString(); }

    @Bean
    public Instant startedAt() { return Instant.now(); }
}
