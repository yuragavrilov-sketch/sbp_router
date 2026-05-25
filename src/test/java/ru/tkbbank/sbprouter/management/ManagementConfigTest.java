package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.Test;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;

import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ManagementConfigTest {
    @Test void usesBaselineWhenNoOverride() {
        var props = new SbpRouterProperties();
        var routing = new SbpRouterProperties.Routing(); routing.setTkbPayEnabled(false); props.setRouting(routing);
        var repo = mock(ConfigOverrideRepository.class); when(repo.load()).thenReturn(Optional.empty());
        var store = new ManagementConfig().configStore(props, repo);
        assertThat(store.current().routing().isTkbPayEnabled()).isFalse();
        assertThat(store.current().version()).isZero();
    }
    @Test void overrideSupersedesBaseline() {
        var props = new SbpRouterProperties();
        var routing = new SbpRouterProperties.Routing(); routing.setTkbPayEnabled(true);
        var override = RouterConfigSnapshot.builder().routing(routing).version(42).build();
        var repo = mock(ConfigOverrideRepository.class); when(repo.load()).thenReturn(Optional.of(override));
        var store = new ManagementConfig().configStore(props, repo);
        assertThat(store.current().routing().isTkbPayEnabled()).isTrue();
        assertThat(store.current().version()).isEqualTo(42);
    }
}
