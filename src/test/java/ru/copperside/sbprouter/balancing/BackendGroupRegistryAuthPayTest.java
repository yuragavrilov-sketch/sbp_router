package ru.copperside.sbprouter.balancing;

import org.junit.jupiter.api.Test;
import ru.copperside.sbprouter.config.SbpRouterProperties;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackendGroupRegistryAuthPayTest {

    private static SbpRouterProperties baseProps() {
        SbpRouterProperties p = new SbpRouterProperties();
        SbpRouterProperties.Group g = new SbpRouterProperties.Group();
        g.setBackends(List.of("http://backend/api"));
        p.getGroups().put("default", g);
        p.setActiveGroup("default");
        return p;
    }

    @Test
    void bootstrapAuthPayDisabledByDefault() {
        BackendGroupRegistry registry = new BackendGroupRegistry(baseProps());
        assertThat(registry.authPayRoute().enabled()).isFalse();
    }

    @Test
    void bootstrapBuildsAuthPayPoolWhenEnabled() {
        SbpRouterProperties p = baseProps();
        p.getAuthPay().setEnabled(true);
        p.getAuthPay().setBackends(List.of("http://authpay/x", "http://authpay/y"));
        p.getAuthPay().setTimeoutMs(1500);

        BackendGroupRegistry registry = new BackendGroupRegistry(p);
        AuthPayRoute route = registry.authPayRoute();
        assertThat(route.enabled()).isTrue();
        assertThat(route.pool().backends()).hasSize(2);
        assertThat(route.timeout()).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    void replaceSwapsAuthPayRoute() {
        BackendGroupRegistry registry = new BackendGroupRegistry(baseProps());
        AuthPayRoute newRoute = new AuthPayRoute(true,
                new BackendGroup("authpay", List.of(new Backend("http://authpay/z", new BackendHealth()))),
                Duration.ofMillis(2000), java.util.Set.of());
        registry.replace(registry.groups(), "default", 5L, newRoute);
        assertThat(registry.authPayRoute().pool().backends()).hasSize(1);
        assertThat(registry.appliedVersion()).isEqualTo(5L);
    }
}
