package ru.copperside.sbprouter.balancing;

import org.junit.jupiter.api.Test;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackendGroupRegistryTest {

    private static SbpRouterProperties props(String active, Map<String, List<String>> groups) {
        SbpRouterProperties p = new SbpRouterProperties();
        p.setActiveGroup(active);
        groups.forEach((name, urls) -> {
            SbpRouterProperties.Group g = new SbpRouterProperties.Group();
            g.setBackends(urls);
            p.getGroups().put(name, g);
        });
        return p;
    }

    @Test
    void buildsAndExposesActiveGroup() {
        BackendGroupRegistry r = new BackendGroupRegistry(
                props("default", Map.of("default", List.of("http://a/api", "http://b/api"))));
        assertThat(r.activeGroupName()).isEqualTo("default");
        assertThat(r.activeGroup().backends()).hasSize(2);
        assertThat(r.groups()).containsKey("default");
    }

    @Test
    void switchesActiveGroup() {
        BackendGroupRegistry r = new BackendGroupRegistry(props("a",
                Map.of("a", List.of("http://a/api"), "b", List.of("http://b/api"))));
        r.setActiveGroup("b");
        assertThat(r.activeGroupName()).isEqualTo("b");
        assertThat(r.activeGroup().backends().get(0).url()).isEqualTo("http://b/api");
    }

    @Test
    void unknownGroupSwitchThrows() {
        BackendGroupRegistry r = new BackendGroupRegistry(
                props("default", Map.of("default", List.of("http://a/api"))));
        assertThatThrownBy(() -> r.setActiveGroup("nope"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(r.activeGroupName()).isEqualTo("default"); // unchanged
    }

    @Test
    void rejectsMissingActiveGroup() {
        assertThatThrownBy(() -> new BackendGroupRegistry(
                props("missing", Map.of("default", List.of("http://a/api")))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsEmptyGroupAndBlankUrl() {
        assertThatThrownBy(() -> new BackendGroupRegistry(props("default", Map.of("default", List.of()))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BackendGroupRegistry(props("default", Map.of("default", List.of(" ")))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNoGroups() {
        assertThatThrownBy(() -> new BackendGroupRegistry(props("default", Map.of())))
                .isInstanceOf(IllegalStateException.class);
    }
}
