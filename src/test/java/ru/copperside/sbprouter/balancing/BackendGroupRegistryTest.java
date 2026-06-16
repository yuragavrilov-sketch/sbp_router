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
    void replaceRejectsActiveGroupNotInNewGroups() {
        BackendGroupRegistry r = new BackendGroupRegistry(
                props("default", Map.of("default", List.of("http://a/api"))));
        Map<String, BackendGroup> groups = Map.of("dr",
                new BackendGroup("dr", List.of(new Backend("http://b/api", new BackendHealth()))));
        assertThatThrownBy(() -> r.replace(groups, "nope", 2L))
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

    @Test
    void replaceSwapsGroupsActiveAndVersion() {
        BackendGroupRegistry r = new BackendGroupRegistry(
                props("default", java.util.Map.of("default", java.util.List.of("http://a/api"))));
        assertThat(r.appliedVersion()).isZero();

        java.util.Map<String, ru.copperside.sbprouter.balancing.BackendGroup> groups = new java.util.LinkedHashMap<>();
        groups.put("dr", new ru.copperside.sbprouter.balancing.BackendGroup("dr",
                java.util.List.of(new ru.copperside.sbprouter.balancing.Backend("http://b/api", new ru.copperside.sbprouter.balancing.BackendHealth()))));
        r.replace(groups, "dr", 5L);

        assertThat(r.activeGroupName()).isEqualTo("dr");
        assertThat(r.groups()).containsOnlyKeys("dr");
        assertThat(r.activeGroup().backends().get(0).url()).isEqualTo("http://b/api");
        assertThat(r.appliedVersion()).isEqualTo(5L);
    }
}
