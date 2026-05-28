package ru.copperside.sbprouter.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationStandardComplianceTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void pomUsesSpringCloudConfigDataDependenciesWithoutBootstrap() throws IOException {
        String pom = read("pom.xml");

        assertThat(pom)
                .contains("<groupId>ru.copperside</groupId>")
                .contains("<spring-cloud.version>2025.1.1</spring-cloud.version>")
                .contains("<artifactId>spring-cloud-dependencies</artifactId>")
                .contains("<artifactId>spring-cloud-starter-config</artifactId>")
                .contains("<artifactId>spring-cloud-starter-vault-config</artifactId>")
                .doesNotContain("spring-cloud-starter-bootstrap");
    }

    @Test
    void applicationYamlUsesConfigServerAndVaultImports() throws IOException {
        String application = read("src/main/resources/application.yml");

        assertThat(application)
                .contains("name: sbp-router")
                .contains("default: local")
                .contains("optional:configserver:")
                .contains("optional:vault://")
                .contains("CONFIG_SERVER_ENABLED:false")
                .contains("VAULT_ENABLED:false")
                .contains("label: ${CONFIG_SERVER_LABEL:${pay.environment}}")
                .doesNotContain("spring-cloud-starter-bootstrap");
    }

    @Test
    void mainResourcesDeclareOnlySupportedProfileFiles() throws IOException {
        Path resources = PROJECT_ROOT.resolve("src/main/resources");

        List<String> applicationFiles;
        try (var files = Files.list(resources)) {
            applicationFiles = files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("application") && name.endsWith(".yml"))
                    .sorted()
                    .toList();
        }

        assertThat(applicationFiles).containsExactly(
                "application-local.yml",
                "application-prod.yml",
                "application-test.yml",
                "application.yml"
        );
    }

    @Test
    void environmentProfilesOnlySelectEnvironmentAndExternalSources() throws IOException {
        assertThat(read("src/main/resources/application-local.yml"))
                .contains("environment: local")
                .contains("CONFIG_SERVER_ENABLED:false")
                .contains("VAULT_ENABLED:false");

        assertThat(read("src/main/resources/application-test.yml"))
                .contains("environment: test")
                .contains("enabled: true")
                .doesNotContain("token:");

        assertThat(read("src/main/resources/application-prod.yml"))
                .contains("environment: prod")
                .contains("enabled: true")
                .doesNotContain("token:");
    }

    @Test
    void bootstrapFilesAreNotPresent() {
        assertThat(PROJECT_ROOT.resolve("src/main/resources/bootstrap.yml")).doesNotExist();
        assertThat(PROJECT_ROOT.resolve("src/main/resources/bootstrap.yaml")).doesNotExist();
        assertThat(PROJECT_ROOT.resolve("src/main/resources/bootstrap.properties")).doesNotExist();
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(PROJECT_ROOT.resolve(relativePath));
    }
}
