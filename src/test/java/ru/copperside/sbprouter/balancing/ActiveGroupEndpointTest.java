package ru.copperside.sbprouter.balancing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveGroupEndpointTest {

    private static BackendGroupRegistry registry() {
        SbpRouterProperties p = new SbpRouterProperties();
        p.setActiveGroup("a");
        SbpRouterProperties.Group ga = new SbpRouterProperties.Group();
        ga.setBackends(List.of("http://a/api"));
        SbpRouterProperties.Group gb = new SbpRouterProperties.Group();
        gb.setBackends(List.of("http://b/api"));
        p.getGroups().put("a", ga);
        p.getGroups().put("b", gb);
        return new BackendGroupRegistry(p);
    }

    @Test
    void readReturnsActiveGroupAndBackends() {
        ActiveGroupEndpoint endpoint = new ActiveGroupEndpoint(registry(), Clock.systemUTC());
        Map<String, Object> state = endpoint.state();
        assertThat(state.get("activeGroup")).isEqualTo("a");
        assertThat(state.get("groups")).isInstanceOf(List.class);
    }

    @Test
    void writeSwitchesActiveGroup() {
        BackendGroupRegistry r = registry();
        ActiveGroupEndpoint endpoint = new ActiveGroupEndpoint(r, Clock.systemUTC());
        WebEndpointResponse<Map<String, Object>> resp = endpoint.selectGroup("b");
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(r.activeGroupName()).isEqualTo("b");
    }

    @Test
    void writeUnknownGroupReturns404() {
        BackendGroupRegistry r = registry();
        ActiveGroupEndpoint endpoint = new ActiveGroupEndpoint(r, Clock.systemUTC());
        WebEndpointResponse<Map<String, Object>> resp = endpoint.selectGroup("nope");
        assertThat(resp.getStatus()).isEqualTo(404);
        assertThat(r.activeGroupName()).isEqualTo("a"); // unchanged
    }
}
