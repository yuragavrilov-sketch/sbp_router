package ru.tkbbank.sbprouter.history;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/** Thread-safe bounded ring buffer of the most recent request records. */
@Component
public class RequestHistoryStore {
    private final int capacity;
    private final Deque<RequestRecord> buffer;

    public RequestHistoryStore(@Value("${sbp-router.history.capacity:1000}") int capacity) {
        this.capacity = capacity; this.buffer = new ArrayDeque<>(capacity);
    }
    public synchronized void add(RequestRecord record) {
        if (buffer.size() >= capacity) buffer.pollFirst();
        buffer.addLast(record);
    }
    public synchronized List<RequestRecord> recent(int limit) {
        if (limit <= 0) return List.of();
        List<RequestRecord> out = new ArrayList<>(Math.min(limit, buffer.size()));
        Iterator<RequestRecord> it = buffer.descendingIterator();
        while (it.hasNext() && out.size() < limit) out.add(it.next());
        return out;
    }
    public synchronized int size() { return buffer.size(); }
    public int capacity() { return capacity; }
}
