package ru.tkbbank.sbprouter.management;

import org.springframework.stereotype.Component;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;
import ru.tkbbank.sbprouter.extraction.FieldRule;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ConfigValidator {
    private static final Set<String> REQUIRED_UPSTREAMS = Set.of("infosrv", "stub-verification", "stub-connector");
    private static final Set<String> KNOWN_REQUEST_TYPES = Set.of("ReqAuthPay", "ReqNoticePay");

    public void validate(RouterConfigSnapshot snapshot) {
        validateTerminals(snapshot.terminals());
        validateUpstreams(snapshot.upstreams());
        validateExtractionRules(snapshot.extractionRules());
    }

    private void validateTerminals(SbpRouterProperties.Terminals t) {
        if (t == null) throw new ConfigValidationException("terminals", "terminals must not be null");
        requireText("terminals.c2bTerminal.fieldName", t.getC2bTerminal().getFieldName());
        requireText("terminals.b2cTerminal.fieldName", t.getB2cTerminal().getFieldName());
        requireText("terminals.b2cTerminal.tkbPayPrefix", t.getB2cTerminal().getTkbPayPrefix());
        if (t.getTkbPayList() != null)
            for (String e : t.getTkbPayList())
                if (e == null || e.isBlank())
                    throw new ConfigValidationException("terminals.tkbPayList", "tkb-pay-list entries must not be blank");
    }

    private void validateUpstreams(Map<String, SbpRouterProperties.UpstreamConfig> upstreams) {
        if (upstreams == null) throw new ConfigValidationException("upstreams", "upstreams must not be null");
        for (String req : REQUIRED_UPSTREAMS)
            if (!upstreams.containsKey(req))
                throw new ConfigValidationException("upstreams." + req, "required upstream '" + req + "' is missing");
        upstreams.forEach((name, cfg) -> {
            String f = "upstreams." + name;
            if (cfg == null) throw new ConfigValidationException(f, "upstream config must not be null");
            requireText(f + ".url", cfg.getUrl());
            try {
                URI uri = new URI(cfg.getUrl());
                if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https")))
                    throw new ConfigValidationException(f + ".url", "url must be http(s)");
            } catch (java.net.URISyntaxException e) {
                throw new ConfigValidationException(f + ".url", "malformed url: " + cfg.getUrl());
            }
            if (cfg.getTimeout() != null && (cfg.getTimeout().isNegative() || cfg.getTimeout().isZero()))
                throw new ConfigValidationException(f + ".timeout", "timeout must be > 0");
            if (cfg.getRetry() != null) {
                if (cfg.getRetry().getMaxAttempts() < 0)
                    throw new ConfigValidationException(f + ".retry.maxAttempts", "maxAttempts must be >= 0");
                if (cfg.getRetry().getBackoff() != null && cfg.getRetry().getBackoff().isNegative())
                    throw new ConfigValidationException(f + ".retry.backoff", "backoff must be >= 0");
            }
        });
    }

    private void validateExtractionRules(Map<String, SbpRouterProperties.ExtractionRuleSet> rules) {
        if (rules == null) throw new ConfigValidationException("extractionRules", "extractionRules must not be null");
        rules.forEach((type, ruleSet) -> {
            String base = "extractionRules." + type;
            if (!KNOWN_REQUEST_TYPES.contains(type))
                throw new ConfigValidationException(base, "unknown request type '" + type + "'");
            Set<String> names = new HashSet<>();
            for (FieldRule r : allRules(ruleSet)) {
                requireText(base + ".name", r.getName());
                if (!names.add(r.getName()))
                    throw new ConfigValidationException(base + "." + r.getName(), "duplicate field name");
                boolean hasParentKey = r.getParent() != null && r.getKey() != null;
                boolean hasPath = r.getPath() != null && !r.getPath().isBlank();
                if (hasParentKey == hasPath)
                    throw new ConfigValidationException(base + "." + r.getName(),
                            "field rule must have exactly one of (parent+key) or (path)");
            }
        });
    }

    private List<FieldRule> allRules(SbpRouterProperties.ExtractionRuleSet rs) {
        var all = new java.util.ArrayList<FieldRule>();
        if (rs.getRoutingFields() != null) all.addAll(rs.getRoutingFields());
        if (rs.getExtraFields() != null) all.addAll(rs.getExtraFields());
        return all;
    }

    private void requireText(String field, String value) {
        if (value == null || value.isBlank()) throw new ConfigValidationException(field, field + " must not be blank");
    }
}
