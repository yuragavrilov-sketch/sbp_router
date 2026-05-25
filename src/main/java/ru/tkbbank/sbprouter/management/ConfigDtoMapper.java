package ru.tkbbank.sbprouter.management;

import org.springframework.stereotype.Component;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;
import ru.tkbbank.sbprouter.extraction.FieldRule;
import ru.tkbbank.sbprouter.management.dto.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ConfigDtoMapper {
    public SbpRouterProperties.Routing toRouting(RoutingConfigDto dto) {
        var r = new SbpRouterProperties.Routing(); r.setTkbPayEnabled(dto.tkbPayEnabled()); return r;
    }
    public SbpRouterProperties.Terminals toTerminals(TerminalsConfigDto dto) {
        var t = new SbpRouterProperties.Terminals();
        var c2b = new SbpRouterProperties.C2bTerminal(); c2b.setFieldName(dto.c2bFieldName()); t.setC2bTerminal(c2b);
        var b2c = new SbpRouterProperties.B2cTerminal(); b2c.setFieldName(dto.b2cFieldName()); b2c.setTkbPayPrefix(dto.b2cPrefix()); t.setB2cTerminal(b2c);
        t.setTkbPayList(dto.tkbPayList() != null ? List.copyOf(dto.tkbPayList()) : List.of());
        return t;
    }
    public Map<String, SbpRouterProperties.UpstreamConfig> toUpstreams(UpstreamsConfigDto dto) {
        Map<String, SbpRouterProperties.UpstreamConfig> out = new LinkedHashMap<>();
        dto.upstreams().forEach((name, u) -> {
            var cfg = new SbpRouterProperties.UpstreamConfig(); cfg.setUrl(u.url());
            if (u.timeoutMillis() != null) cfg.setTimeout(Duration.ofMillis(u.timeoutMillis()));
            var retry = new SbpRouterProperties.RetryConfig();
            if (u.maxAttempts() != null) retry.setMaxAttempts(u.maxAttempts());
            if (u.backoffMillis() != null) retry.setBackoff(Duration.ofMillis(u.backoffMillis()));
            cfg.setRetry(retry); out.put(name, cfg);
        });
        return out;
    }
    public Map<String, SbpRouterProperties.ExtractionRuleSet> toExtractionRules(ExtractionRulesConfigDto dto) {
        Map<String, SbpRouterProperties.ExtractionRuleSet> out = new LinkedHashMap<>();
        dto.extractionRules().forEach((type, set) -> {
            var rs = new SbpRouterProperties.ExtractionRuleSet();
            rs.setRoutingFields(toFieldRules(set.routingFields())); rs.setExtraFields(toFieldRules(set.extraFields()));
            out.put(type, rs);
        });
        return out;
    }
    private List<FieldRule> toFieldRules(List<FieldRuleDto> dtos) {
        if (dtos == null) return List.of();
        return dtos.stream().map(d -> { var r = new FieldRule(); r.setName(d.name()); r.setParent(d.parent()); r.setKey(d.key()); r.setPath(d.path()); return r; }).collect(Collectors.toList());
    }

    public ConfigSnapshotDto toSnapshotDto(RouterConfigSnapshot s) {
        return new ConfigSnapshotDto(s.version(), s.updatedAt().toString(),
                new RoutingConfigDto(s.routing().isTkbPayEnabled()), toTerminalsDto(s.terminals()),
                toUpstreamsDto(s.upstreams()), toExtractionRulesDto(s.extractionRules()));
    }
    private TerminalsConfigDto toTerminalsDto(SbpRouterProperties.Terminals t) {
        return new TerminalsConfigDto(t.getC2bTerminal().getFieldName(), t.getB2cTerminal().getFieldName(),
                t.getB2cTerminal().getTkbPayPrefix(), t.getTkbPayList() != null ? List.copyOf(t.getTkbPayList()) : List.of());
    }
    private Map<String, UpstreamDto> toUpstreamsDto(Map<String, SbpRouterProperties.UpstreamConfig> ups) {
        Map<String, UpstreamDto> out = new LinkedHashMap<>();
        ups.forEach((name, cfg) -> out.put(name, new UpstreamDto(cfg.getUrl(),
                cfg.getTimeout() != null ? cfg.getTimeout().toMillis() : null,
                cfg.getRetry() != null ? cfg.getRetry().getMaxAttempts() : null,
                cfg.getRetry() != null && cfg.getRetry().getBackoff() != null ? cfg.getRetry().getBackoff().toMillis() : null)));
        return out;
    }
    private Map<String, ExtractionRuleSetDto> toExtractionRulesDto(Map<String, SbpRouterProperties.ExtractionRuleSet> rules) {
        Map<String, ExtractionRuleSetDto> out = new LinkedHashMap<>();
        rules.forEach((type, set) -> out.put(type, new ExtractionRuleSetDto(toFieldRuleDtos(set.getRoutingFields()), toFieldRuleDtos(set.getExtraFields()))));
        return out;
    }
    private List<FieldRuleDto> toFieldRuleDtos(List<FieldRule> rules) {
        if (rules == null) return List.of();
        return rules.stream().map(r -> new FieldRuleDto(r.getName(), r.getParent(), r.getKey(), r.getPath())).collect(Collectors.toList());
    }
}
