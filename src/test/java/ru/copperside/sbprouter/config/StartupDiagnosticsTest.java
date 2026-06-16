package ru.copperside.sbprouter.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class StartupDiagnosticsTest {

    @Test
    void logsConfigurationSummary(CapturedOutput output) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "sbp-router")
                .withProperty("pay.environment", "local")
                .withProperty("spring.profiles.active", "local")
                .withProperty("server.port", "8080")
                .withProperty(
                        "spring.config.import",
                        "optional:configserver:http://pay-payconfig-server:8080,optional:vault://"
                )
                .withProperty("spring.cloud.config.enabled", "false")
                .withProperty("spring.cloud.vault.enabled", "false");

        SbpRouterProperties routerProperties = new SbpRouterProperties();
        routerProperties.getBackend().setUrl("http://infosrv.bank.local/api/gcsvc");

        new StartupDiagnostics(environment, routerProperties).logConfiguration();

        assertThat(output)
                .contains("sbp-router")
                .contains("local")
                .contains("configserver")
                .contains("vault")
                .contains("infosrv.bank.local");
    }
}
