package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.Test;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;

import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigServiceTest {
    private Map<String, SbpRouterProperties.UpstreamConfig> upstreams() {
        var u = new SbpRouterProperties.UpstreamConfig(); u.setUrl("http://infosrv/api");
        var sv = new SbpRouterProperties.UpstreamConfig(); sv.setUrl("http://x/v");
        var sc = new SbpRouterProperties.UpstreamConfig(); sc.setUrl("http://x/c");
        return Map.of("infosrv", u, "stub-verification", sv, "stub-connector", sc);
    }
    private ConfigStore storeAtVersion(long v) {
        return new ConfigStore(RouterConfigSnapshot.builder().upstreams(upstreams()).version(v).build());
    }

    @Test void updateRoutingBumpsVersionValidatesPersistsAndApplies() {
        var store = storeAtVersion(3); var repo = mock(ConfigOverrideRepository.class);
        var service = new ConfigService(store, new ConfigValidator(), repo);
        var routing = new SbpRouterProperties.Routing(); routing.setTkbPayEnabled(true);
        var result = service.updateRouting(routing, 3);
        assertThat(result.version()).isEqualTo(4);
        assertThat(store.current().routing().isTkbPayEnabled()).isTrue();
        verify(repo).save(argThat(s -> s.version() == 4));
    }
    @Test void rejectsStaleVersion() {
        var store = storeAtVersion(3); var repo = mock(ConfigOverrideRepository.class);
        var service = new ConfigService(store, new ConfigValidator(), repo);
        assertThatThrownBy(() -> service.updateRouting(new SbpRouterProperties.Routing(), 2)).isInstanceOf(VersionConflictException.class);
        verifyNoInteractions(repo);
    }
    @Test void invalidUpdateIsNotPersistedNorApplied() {
        var store = storeAtVersion(3); var repo = mock(ConfigOverrideRepository.class);
        var service = new ConfigService(store, new ConfigValidator(), repo);
        assertThatThrownBy(() -> service.updateUpstreams(Map.of(), 3)).isInstanceOf(ConfigValidationException.class);
        verifyNoInteractions(repo);
        assertThat(store.current().version()).isEqualTo(3);
    }
    @Test void persistFailureLeavesMemoryUnchanged() {
        var store = storeAtVersion(3); var repo = mock(ConfigOverrideRepository.class);
        doThrow(new RuntimeException("disk full")).when(repo).save(any());
        var service = new ConfigService(store, new ConfigValidator(), repo);
        var routing = new SbpRouterProperties.Routing(); routing.setTkbPayEnabled(true);
        assertThatThrownBy(() -> service.updateRouting(routing, 3)).isInstanceOf(RuntimeException.class);
        assertThat(store.current().version()).isEqualTo(3);
        assertThat(store.current().routing().isTkbPayEnabled()).isFalse();
    }
}
