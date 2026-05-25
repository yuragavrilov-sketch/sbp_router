package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.Test;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;
import ru.tkbbank.sbprouter.management.dto.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigDtoMapperTest {
    private final ConfigDtoMapper mapper = new ConfigDtoMapper();
    @Test void mapsUpstreamDtoToConfigWithDurations() {
        var dto = new UpstreamsConfigDto(Map.of("infosrv", new UpstreamDto("http://x/api", 30000L, 2, 500L)));
        var cfg = mapper.toUpstreams(dto).get("infosrv");
        assertThat(cfg.getUrl()).isEqualTo("http://x/api");
        assertThat(cfg.getTimeout()).isEqualTo(Duration.ofMillis(30000));
        assertThat(cfg.getRetry().getMaxAttempts()).isEqualTo(2);
        assertThat(cfg.getRetry().getBackoff()).isEqualTo(Duration.ofMillis(500));
    }
    @Test void mapsExtractionRulesDtoToInternal() {
        var dto = new ExtractionRulesConfigDto(Map.of("ReqAuthPay", new ExtractionRuleSetDto(
                List.of(new FieldRuleDto("terminalName", "PayProfile", "Tran.TermName", null)),
                List.of(new FieldRuleDto("amount", null, null, "/a/b")))));
        var rule = mapper.toExtractionRules(dto).get("ReqAuthPay").getRoutingFields().get(0);
        assertThat(rule.getName()).isEqualTo("terminalName");
        assertThat(rule.isNamedBlock()).isTrue();
    }
    @Test void mapsSnapshotToDto() {
        var routing = new SbpRouterProperties.Routing(); routing.setTkbPayEnabled(true);
        var dto = mapper.toSnapshotDto(RouterConfigSnapshot.builder().routing(routing).version(9).build());
        assertThat(dto.version()).isEqualTo(9);
        assertThat(dto.routing().tkbPayEnabled()).isTrue();
        assertThat(dto.updatedAt()).isNotBlank();
    }
}
