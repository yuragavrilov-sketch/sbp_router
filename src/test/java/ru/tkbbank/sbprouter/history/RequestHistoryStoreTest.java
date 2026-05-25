package ru.tkbbank.sbprouter.history;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;

class RequestHistoryStoreTest {
    private RequestRecord rec(String id) {
        return new RequestRecord(Instant.now(), id, "ReqAuthPay", "T", "EXTERNAL", "B2C", "infosrv", 200, 5, null);
    }
    @Test void recentReturnsMostRecentFirst() {
        var s = new RequestHistoryStore(10); s.add(rec("a")); s.add(rec("b"));
        assertThat(s.recent(10)).extracting(RequestRecord::correlationId).containsExactly("b", "a");
    }
    @Test void evictsOldestBeyondCapacity() {
        var s = new RequestHistoryStore(3); IntStream.rangeClosed(1, 5).forEach(i -> s.add(rec("r" + i)));
        assertThat(s.size()).isEqualTo(3);
        assertThat(s.recent(10)).extracting(RequestRecord::correlationId).containsExactly("r5", "r4", "r3");
    }
    @Test void recentRespectsLimit() {
        var s = new RequestHistoryStore(10); IntStream.rangeClosed(1, 5).forEach(i -> s.add(rec("r" + i)));
        assertThat(s.recent(2)).hasSize(2);
    }
    @Test void recentWithNonPositiveLimitReturnsEmpty() {
        var s = new RequestHistoryStore(10);
        s.add(rec("a"));
        org.assertj.core.api.Assertions.assertThat(s.recent(0)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(s.recent(-5)).isEmpty();
    }
    @Test void concurrentAddsKeepCapacityInvariant() throws InterruptedException {
        var s = new RequestHistoryStore(100);
        var threads = IntStream.range(0, 8).mapToObj(t -> new Thread(() -> { for (int i = 0; i < 1000; i++) s.add(rec("t" + t + "-" + i)); })).toList();
        threads.forEach(Thread::start); for (var th : threads) th.join();
        assertThat(s.size()).isEqualTo(100);
    }
}
