package ru.copperside.sbprouter.balancing;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A named, ordered set of backends with a round-robin cursor. */
public class BackendGroup {

    private final String name;
    private final List<Backend> backends;
    private final AtomicInteger rrCursor = new AtomicInteger(0);

    public BackendGroup(String name, List<Backend> backends) {
        this.name = name;
        this.backends = List.copyOf(backends);
    }

    public String name() { return name; }

    public List<Backend> backends() { return backends; }

    /** Next round-robin start index in [0, size); advances by one per call, overflow-safe. */
    public int nextStartIndex(int size) {
        if (size <= 0) {
            return 0;
        }
        return (rrCursor.getAndIncrement() & Integer.MAX_VALUE) % size;
    }
}
