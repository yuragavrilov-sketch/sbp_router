package ru.tkbbank.sbprouter.management;

import org.junit.jupiter.api.Test;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigStoreTest {
    @Test void currentReturnsInitialSnapshot() {
        var initial = RouterConfigSnapshot.builder().version(1).build();
        assertThat(new ConfigStore(initial).current()).isSameAs(initial);
    }
    @Test void replaceSwapsSnapshotAtomically() {
        var store = new ConfigStore(RouterConfigSnapshot.builder().version(1).build());
        store.replace(RouterConfigSnapshot.builder().version(2).build());
        assertThat(store.current().version()).isEqualTo(2);
    }
}
