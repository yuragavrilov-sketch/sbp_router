package ru.copperside.sbprouter.manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.copperside.sbprouter.config.SbpRouterProperties.B2cTerminal;
import ru.copperside.sbprouter.config.SbpRouterProperties.C2bTerminal;
import ru.copperside.sbprouter.config.SbpRouterProperties.ExtractionRuleSet;
import ru.copperside.sbprouter.config.SbpRouterProperties.RetryConfig;
import ru.copperside.sbprouter.config.SbpRouterProperties.Routing;
import ru.copperside.sbprouter.config.SbpRouterProperties.Terminals;
import ru.copperside.sbprouter.config.SbpRouterProperties.UpstreamConfig;
import ru.copperside.sbprouter.extraction.FieldRule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts a fetched manifest DTO into a RoutingConfigSnapshot, validating it first. */
@Component
public class ManifestAdapter {

    private static final Logger log = LoggerFactory.getLogger(ManifestAdapter.class);
    private static final String TKB_PAY_ENABLED = "tkb-pay-enabled";

    public RoutingConfigSnapshot toSnapshot(ManifestDtos.ManifestDto dto) {
        ManifestDtos.ManifestPayload p = dto.payload();
        if (p == null) {
            throw new ManifestValidationException("manifest payload is null");
        }
        Map<String, UpstreamConfig> upstreams = toUpstreams(p.upstreams());
        Map<String, ExtractionRuleSet> rules = toRules(p.extractionRules());
        Terminals terminals = toTerminals(p.terminals());
        Routing routing = toRouting(p.routing());
        validate(upstreams, rules);
        return new RoutingConfigSnapshot(rules, terminals, routing, upstreams, dto.version(), dto.checksum());
    }

    private Map<String, UpstreamConfig> toUpstreams(Map<String, ManifestDtos.UpstreamDto> src) {
        Map<String, UpstreamConfig> out = new LinkedHashMap<>();
        if (src == null) {
            return out;
        }
        src.forEach((name, u) -> {
            UpstreamConfig c = new UpstreamConfig();
            c.setUrl(u.url());
            if (u.timeoutMs() != null) {
                c.setTimeout(Duration.ofMillis(u.timeoutMs()));
            }
            RetryConfig rc = new RetryConfig();
            if (u.retry() != null) {
                if (u.retry().maxAttempts() != null) {
                    rc.setMaxAttempts(u.retry().maxAttempts());
                }
                if (u.retry().backoffMs() != null) {
                    rc.setBackoff(Duration.ofMillis(u.retry().backoffMs()));
                }
            }
            c.setRetry(rc);
            out.put(name, c);
        });
        return out;
    }

    private Map<String, ExtractionRuleSet> toRules(Map<String, ManifestDtos.ExtractionRuleDto> src) {
        Map<String, ExtractionRuleSet> out = new LinkedHashMap<>();
        if (src == null) {
            return out;
        }
        src.forEach((type, r) -> {
            ExtractionRuleSet set = new ExtractionRuleSet();
            set.setRoutingFields(toFieldRules(r.routingFields()));
            set.setExtraFields(toFieldRules(r.extraFields()));
            out.put(type, set);
        });
        return out;
    }

    private List<FieldRule> toFieldRules(List<ManifestDtos.FieldBindingDto> bindings) {
        List<FieldRule> out = new ArrayList<>();
        if (bindings == null) {
            return out;
        }
        for (ManifestDtos.FieldBindingDto b : bindings) {
            FieldRule fr = new FieldRule();
            fr.setName(b.name());
            fr.setParent(b.parent());
            fr.setKey(b.key());
            fr.setPath(b.path());
            out.add(fr);
        }
        return out;
    }

    private Terminals toTerminals(ManifestDtos.TerminalsDto src) {
        Terminals t = new Terminals();
        if (src == null) {
            return t;
        }
        C2bTerminal c2b = new C2bTerminal();
        if (src.c2bFieldName() != null) {
            c2b.setFieldName(src.c2bFieldName());
        }
        B2cTerminal b2c = new B2cTerminal();
        if (src.b2cFieldName() != null) {
            b2c.setFieldName(src.b2cFieldName());
        }
        if (src.tkbPayPrefix() != null) {
            b2c.setTkbPayPrefix(src.tkbPayPrefix());
        }
        t.setC2bTerminal(c2b);
        t.setB2cTerminal(b2c);
        t.setTkbPayList(src.tkbPayList() != null ? src.tkbPayList() : List.of());
        return t;
    }

    private Routing toRouting(Map<String, String> flags) {
        Routing r = new Routing();
        if (flags == null) {
            return r;
        }
        flags.forEach((k, v) -> {
            if (TKB_PAY_ENABLED.equals(k)) {
                r.setTkbPayEnabled(Boolean.parseBoolean(v));
            } else {
                log.warn("ignoring unknown routing flag '{}'", k);
            }
        });
        return r;
    }

    private void validate(Map<String, UpstreamConfig> upstreams, Map<String, ExtractionRuleSet> rules) {
        if (upstreams.isEmpty()) {
            throw new ManifestValidationException("manifest has no upstreams");
        }
        rules.forEach((type, set) -> {
            List<FieldRule> all = new ArrayList<>();
            if (set.getRoutingFields() != null) {
                all.addAll(set.getRoutingFields());
            }
            if (set.getExtraFields() != null) {
                all.addAll(set.getExtraFields());
            }
            for (FieldRule fr : all) {
                boolean named = fr.getParent() != null && fr.getKey() != null;
                boolean xpath = fr.getPath() != null;
                if (named == xpath) {
                    throw new ManifestValidationException(
                            "invalid field binding '" + fr.getName() + "' in " + type
                                    + ": requires (parent+key) XOR path");
                }
            }
        });
    }
}
