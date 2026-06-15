package ru.copperside.sbprouter.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SbpRouterPropertiesTest {

    @Autowired
    private SbpRouterProperties props;

    @Test
    void extractionRulesLoaded() {
        assertThat(props.getExtractionRules()).containsKeys("ReqAuthPay", "ReqNoticePay");
        var authRules = props.getExtractionRules().get("ReqAuthPay");
        assertThat(authRules.getRoutingFields()).hasSize(4);
        assertThat(authRules.getExtraFields()).hasSize(2);
        assertThat(authRules.getRoutingFields().get(0).getName()).isEqualTo("terminalName");
        assertThat(authRules.getRoutingFields().get(0).isNamedBlock()).isTrue();
        var noticeRules = props.getExtractionRules().get("ReqNoticePay");
        assertThat(noticeRules.getRoutingFields()).hasSize(5);
        assertThat(noticeRules.getRoutingFields().get(4).getPath())
                .isEqualTo("/Document/GCSvc/Payment/ReqNoticePay/State");
    }

    @Test
    void terminalsConfigLoaded() {
        assertThat(props.getTerminals().getTkbPayList()).contains("MB0000700543", "MB0000004185");
        assertThat(props.getTerminals().getC2bTerminal().getFieldName()).isEqualTo("rcvTspId");
        assertThat(props.getTerminals().getB2cTerminal().getFieldName()).isEqualTo("terminalName");
        assertThat(props.getTerminals().getB2cTerminal().getTkbPayPrefix()).isEqualTo("Pay");
    }

    @Test
    void routingConfigLoaded() {
        assertThat(props.getRouting().isTkbPayEnabled()).isFalse();
    }

    @Test
    void upstreamsConfigLoaded() {
        assertThat(props.getUpstreams()).containsKeys("infosrv", "tkbpay-verification", "tkbpay-connector");
        assertThat(props.getUpstreams().get("infosrv").getTimeout()).isNotNull();
        assertThat(props.getUpstreams().get("infosrv").getUrl()).isNotBlank();
        assertThat(props.getUpstreams().get("infosrv").getRetry()).isNotNull();
    }

    @Test
    void kafkaDefaultsAndAccessors() {
        SbpRouterProperties props = new SbpRouterProperties();

        // defaults
        assertThat(props.getKafka().isEnabled()).isFalse();
        assertThat(props.getKafka().getBootstrapServers()).isEqualTo("localhost:9092");
        assertThat(props.getKafka().getTopic()).isEqualTo("sbp-router-traffic");

        // setters
        props.getKafka().setEnabled(true);
        props.getKafka().setBootstrapServers("kafka:9092");
        props.getKafka().setTopic("custom-topic");
        assertThat(props.getKafka().isEnabled()).isTrue();
        assertThat(props.getKafka().getBootstrapServers()).isEqualTo("kafka:9092");
        assertThat(props.getKafka().getTopic()).isEqualTo("custom-topic");
    }

}
