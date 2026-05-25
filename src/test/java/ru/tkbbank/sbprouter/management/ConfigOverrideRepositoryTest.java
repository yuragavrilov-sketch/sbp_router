package ru.tkbbank.sbprouter.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;

import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigOverrideRepositoryTest {
    private ObjectMapper mapper() { return new ObjectMapper().registerModule(new JavaTimeModule()); }

    @Test void loadReturnsEmptyWhenFileMissing(@TempDir Path dir) {
        assertThat(new ConfigOverrideRepository(mapper(), dir.resolve("nope.json").toString()).load()).isEmpty();
    }
    @Test void saveThenLoadRoundTrips(@TempDir Path dir) {
        var repo = new ConfigOverrideRepository(mapper(), dir.resolve("overrides.json").toString());
        var routing = new SbpRouterProperties.Routing(); routing.setTkbPayEnabled(true);
        var upstream = new SbpRouterProperties.UpstreamConfig(); upstream.setUrl("http://x/api");
        var snap = RouterConfigSnapshot.builder().routing(routing).upstreams(Map.of("infosrv", upstream)).version(7).build();
        repo.save(snap);
        var loaded = repo.load();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().version()).isEqualTo(7);
        assertThat(loaded.get().routing().isTkbPayEnabled()).isTrue();
        assertThat(loaded.get().upstreams().get("infosrv").getUrl()).isEqualTo("http://x/api");
    }
}
