package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.Test;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;
import ru.tkbbank.sbprouter.extraction.FieldRule;

import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ConfigValidatorTest {
    private final ConfigValidator validator = new ConfigValidator();

    private SbpRouterProperties.UpstreamConfig upstream(String url) {
        var u = new SbpRouterProperties.UpstreamConfig(); u.setUrl(url); return u;
    }
    private Map<String, SbpRouterProperties.UpstreamConfig> validUpstreams() {
        return Map.of("infosrv", upstream("http://infosrv/api"),
                "stub-verification", upstream("http://localhost:8080/stub/verification"),
                "stub-connector", upstream("http://localhost:8080/stub/connector"));
    }
    private RouterConfigSnapshot.Builder validBase() {
        var t = new SbpRouterProperties.Terminals(); t.setTkbPayList(List.of("MB0000700543"));
        return RouterConfigSnapshot.builder().terminals(t).upstreams(validUpstreams());
    }

    @Test void acceptsValidSnapshot() { validator.validate(validBase().build()); }

    @Test void rejectsMissingInfosrv() {
        var snap = validBase().upstreams(Map.of("stub-verification", upstream("http://x/y"),
                "stub-connector", upstream("http://x/c"))).build();
        var ex = catchThrowableOfType(() -> validator.validate(snap), ConfigValidationException.class);
        assertThat(ex.getField()).isEqualTo("upstreams.infosrv");
    }
    @Test void rejectsMalformedUpstreamUrl() {
        var snap = validBase().upstreams(Map.of("infosrv", upstream("not a url"),
                "stub-verification", upstream("http://x/y"), "stub-connector", upstream("http://x/z"))).build();
        assertThatThrownBy(() -> validator.validate(snap)).isInstanceOf(ConfigValidationException.class);
    }
    @Test void rejectsBlankB2cPrefix() {
        var t = new SbpRouterProperties.Terminals(); t.getB2cTerminal().setTkbPayPrefix("  ");
        var ex = catchThrowableOfType(() -> validator.validate(validBase().terminals(t).build()), ConfigValidationException.class);
        assertThat(ex.getField()).isEqualTo("terminals.b2cTerminal.tkbPayPrefix");
    }
    @Test void rejectsUnknownRequestType() {
        var rule = new FieldRule(); rule.setName("x"); rule.setPath("/a/b");
        var rs = new SbpRouterProperties.ExtractionRuleSet(); rs.setRoutingFields(List.of(rule));
        var ex = catchThrowableOfType(() -> validator.validate(validBase().extractionRules(Map.of("BogusType", rs)).build()), ConfigValidationException.class);
        assertThat(ex.getField()).startsWith("extractionRules.BogusType");
    }
    @Test void rejectsFieldRuleWithNeitherPathNorParentKey() {
        var rule = new FieldRule(); rule.setName("x");
        var rs = new SbpRouterProperties.ExtractionRuleSet(); rs.setRoutingFields(List.of(rule));
        assertThatThrownBy(() -> validator.validate(validBase().extractionRules(Map.of("ReqAuthPay", rs)).build()))
                .isInstanceOf(ConfigValidationException.class);
    }
    @Test void rejectsDuplicateFieldNamesInRuleSet() {
        var r1 = new FieldRule(); r1.setName("dup"); r1.setPath("/a");
        var r2 = new FieldRule(); r2.setName("dup"); r2.setPath("/b");
        var rs = new SbpRouterProperties.ExtractionRuleSet(); rs.setRoutingFields(List.of(r1)); rs.setExtraFields(List.of(r2));
        assertThatThrownBy(() -> validator.validate(validBase().extractionRules(Map.of("ReqAuthPay", rs)).build()))
                .isInstanceOf(ConfigValidationException.class);
    }
    @Test void rejectsNonPositiveUpstreamTimeout() {
        var u = upstream("http://infosrv/api"); u.setTimeout(java.time.Duration.ZERO);
        var snap = validBase().upstreams(java.util.Map.of(
                "infosrv", u,
                "stub-verification", upstream("http://x/y"),
                "stub-connector", upstream("http://x/z"))).build();
        var ex = org.assertj.core.api.Assertions.catchThrowableOfType(() -> validator.validate(snap), ConfigValidationException.class);
        org.assertj.core.api.Assertions.assertThat(ex.getField()).endsWith(".timeout");
    }
}
