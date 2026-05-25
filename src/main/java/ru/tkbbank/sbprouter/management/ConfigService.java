package ru.tkbbank.sbprouter.management;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;
import ru.tkbbank.sbprouter.observability.MetricsService;

import java.util.Map;
import java.util.function.Function;

/** Orchestrates a config change: version check -> build -> validate -> persist -> apply. */
@Service
public class ConfigService {
    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);
    private final ConfigStore store;
    private final ConfigValidator validator;
    private final ConfigOverrideRepository overrideRepository;
    private final MetricsService metrics;

    public ConfigService(ConfigStore store, ConfigValidator validator, ConfigOverrideRepository overrideRepository,
                         MetricsService metrics) {
        this.store = store; this.validator = validator; this.overrideRepository = overrideRepository;
        this.metrics = metrics;
    }

    public synchronized RouterConfigSnapshot updateRouting(SbpRouterProperties.Routing r, long expectedVersion) {
        return apply(expectedVersion, b -> b.routing(r));
    }
    public synchronized RouterConfigSnapshot updateTerminals(SbpRouterProperties.Terminals t, long expectedVersion) {
        return apply(expectedVersion, b -> b.terminals(t));
    }
    public synchronized RouterConfigSnapshot updateUpstreams(Map<String, SbpRouterProperties.UpstreamConfig> u, long expectedVersion) {
        return apply(expectedVersion, b -> b.upstreams(u));
    }
    public synchronized RouterConfigSnapshot updateExtractionRules(Map<String, SbpRouterProperties.ExtractionRuleSet> rules, long expectedVersion) {
        return apply(expectedVersion, b -> b.extractionRules(rules));
    }

    private synchronized RouterConfigSnapshot apply(long expectedVersion, Function<RouterConfigSnapshot.Builder, RouterConfigSnapshot.Builder> patch) {
        RouterConfigSnapshot current = store.current();
        if (current.version() != expectedVersion) throw new VersionConflictException(expectedVersion, current.version());
        RouterConfigSnapshot next = patch.apply(RouterConfigSnapshot.builder(current)).version(current.version() + 1).build();
        validator.validate(next);       // -> 400
        overrideRepository.save(next);   // -> 500, память не трогаем
        store.replace(next);             // применяем только после успешной записи
        log.info("Config reloaded: version {} -> {}", current.version(), next.version());
        metrics.recordConfigReload();
        return next;
    }
}
