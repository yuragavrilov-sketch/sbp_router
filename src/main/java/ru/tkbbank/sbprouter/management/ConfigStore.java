package ru.tkbbank.sbprouter.management;

import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import java.util.concurrent.atomic.AtomicReference;

/** Holds the live config snapshot. Reads are lock-free; writes swap atomically. */
public class ConfigStore {
    private final AtomicReference<RouterConfigSnapshot> ref;
    public ConfigStore(RouterConfigSnapshot initial) { this.ref = new AtomicReference<>(initial); }
    public RouterConfigSnapshot current() { return ref.get(); }
    public void replace(RouterConfigSnapshot snapshot) { ref.set(snapshot); }
}
