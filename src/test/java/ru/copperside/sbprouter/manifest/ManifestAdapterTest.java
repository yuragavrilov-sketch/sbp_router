package ru.copperside.sbprouter.manifest;

import org.junit.jupiter.api.Test;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestAdapterTest {

    private final ManifestAdapter adapter = new ManifestAdapter();

    private ManifestDtos.ManifestDto dto(ManifestDtos.ManifestPayload payload) {
        return new ManifestDtos.ManifestDto(5, "sha256:xyz", payload);
    }

    @Test
    void convertsUpstreamsTimeoutAndRetryFromMillis() {
        var payload = new ManifestDtos.ManifestPayload(
                Map.of(),
                new ManifestDtos.TerminalsDto("rcvTspId", "terminalName", "Pay", List.of("MB1")),
                Map.of("tkb-pay-enabled", "true"),
                Map.of("infosrv", new ManifestDtos.UpstreamDto("http://u", 30000,
                        new ManifestDtos.RetryDto(2, 500))));

        RoutingConfigSnapshot s = adapter.toSnapshot(dto(payload));

        SbpRouterProperties.UpstreamConfig u = s.upstreams().get("infosrv");
        assertThat(u.getUrl()).isEqualTo("http://u");
        assertThat(u.getTimeout()).isEqualTo(Duration.ofMillis(30000));
        assertThat(u.getRetry().getMaxAttempts()).isEqualTo(2);
        assertThat(u.getRetry().getBackoff()).isEqualTo(Duration.ofMillis(500));
        assertThat(s.version()).isEqualTo(5);
        assertThat(s.checksum()).isEqualTo("sha256:xyz");
    }

    @Test
    void unflattensTerminalsAndParsesRoutingFlag() {
        var payload = new ManifestDtos.ManifestPayload(
                Map.of(),
                new ManifestDtos.TerminalsDto("rcvTspId", "terminalName", "Pay", List.of("MB1", "MB2")),
                Map.of("tkb-pay-enabled", "true"),
                Map.of("infosrv", new ManifestDtos.UpstreamDto("http://u", null, null)));

        RoutingConfigSnapshot s = adapter.toSnapshot(dto(payload));

        assertThat(s.terminals().getC2bTerminal().getFieldName()).isEqualTo("rcvTspId");
        assertThat(s.terminals().getB2cTerminal().getFieldName()).isEqualTo("terminalName");
        assertThat(s.terminals().getB2cTerminal().getTkbPayPrefix()).isEqualTo("Pay");
        assertThat(s.terminals().getTkbPayList()).containsExactly("MB1", "MB2");
        assertThat(s.routing().isTkbPayEnabled()).isTrue();
    }

    @Test
    void missingRoutingFlagDefaultsToFalse() {
        var payload = new ManifestDtos.ManifestPayload(
                Map.of(),
                new ManifestDtos.TerminalsDto("rcvTspId", "terminalName", "Pay", List.of()),
                Map.of(),
                Map.of("infosrv", new ManifestDtos.UpstreamDto("http://u", null, null)));

        assertThat(adapter.toSnapshot(dto(payload)).routing().isTkbPayEnabled()).isFalse();
    }

    @Test
    void convertsFieldBindingsToRules() {
        var rule = new ManifestDtos.ExtractionRuleDto(
                List.of(new ManifestDtos.FieldBindingDto("rcvTspId", "PayProfile", "RcvTSPId", null)),
                List.of(new ManifestDtos.FieldBindingDto("amount", null, null,
                        "/Document/GCSvc/Payment/ReqAuthPay/Funds/Amount")));
        var payload = new ManifestDtos.ManifestPayload(
                Map.of("ReqAuthPay", rule),
                new ManifestDtos.TerminalsDto("rcvTspId", "terminalName", "Pay", List.of()),
                Map.of(),
                Map.of("infosrv", new ManifestDtos.UpstreamDto("http://u", null, null)));

        RoutingConfigSnapshot s = adapter.toSnapshot(dto(payload));

        var set = s.extractionRules().get("ReqAuthPay");
        assertThat(set.getRoutingFields().get(0).getName()).isEqualTo("rcvTspId");
        assertThat(set.getRoutingFields().get(0).getParent()).isEqualTo("PayProfile");
        assertThat(set.getRoutingFields().get(0).getKey()).isEqualTo("RcvTSPId");
        assertThat(set.getExtraFields().get(0).getPath()).contains("/Funds/Amount");
    }

    @Test
    void rejectsManifestWithNoUpstreams() {
        var payload = new ManifestDtos.ManifestPayload(
                Map.of(),
                new ManifestDtos.TerminalsDto("rcvTspId", "terminalName", "Pay", List.of()),
                Map.of(),
                Map.of());

        assertThatThrownBy(() -> adapter.toSnapshot(dto(payload)))
                .isInstanceOf(ManifestValidationException.class);
    }

    @Test
    void rejectsInvalidFieldBinding() {
        var rule = new ManifestDtos.ExtractionRuleDto(
                List.of(new ManifestDtos.FieldBindingDto("x", "P", "K", "/some/path")), List.of());
        var payload = new ManifestDtos.ManifestPayload(
                Map.of("ReqAuthPay", rule),
                new ManifestDtos.TerminalsDto("rcvTspId", "terminalName", "Pay", List.of()),
                Map.of(),
                Map.of("infosrv", new ManifestDtos.UpstreamDto("http://u", null, null)));

        assertThatThrownBy(() -> adapter.toSnapshot(dto(payload)))
                .isInstanceOf(ManifestValidationException.class);
    }
}
