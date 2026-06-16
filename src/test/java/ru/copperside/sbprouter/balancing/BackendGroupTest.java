package ru.copperside.sbprouter.balancing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackendGroupTest {

    @Test
    void nextStartIndexRotatesAndStaysNonNegative() {
        BackendGroup g = new BackendGroup("default", List.of(
                new Backend("http://a", new BackendHealth()),
                new Backend("http://b", new BackendHealth())));
        assertThat(g.name()).isEqualTo("default");
        assertThat(g.backends()).hasSize(2);
        int i0 = g.nextStartIndex(2);
        int i1 = g.nextStartIndex(2);
        int i2 = g.nextStartIndex(2);
        assertThat(List.of(i0, i1, i2)).allSatisfy(i -> assertThat(i).isBetween(0, 1));
        assertThat(i1).isNotEqualTo(i0); // advanced
    }
}
