package ru.tkbbank.sbprouter.management;

import org.springframework.web.bind.annotation.*;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.history.RequestHistoryStore;
import ru.tkbbank.sbprouter.management.dto.RequestRecordDto;
import ru.tkbbank.sbprouter.management.dto.StatusDto;

import java.lang.management.ManagementFactory;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminMonitoringController {
    private final RequestHistoryStore history;
    private final ConfigStore configStore;
    public AdminMonitoringController(RequestHistoryStore history, ConfigStore configStore) {
        this.history = history; this.configStore = configStore;
    }
    @GetMapping("/requests")
    public List<RequestRecordDto> requests(@RequestParam(defaultValue = "100") int limit) {
        return history.recent(limit).stream().map(r -> new RequestRecordDto(
                r.timestamp() != null ? r.timestamp().toString() : null, r.correlationId(), r.requestType(),
                r.terminal(), r.terminalOwner(), r.sbpOperType(), r.routeDecision(), r.upstreamStatusCode(), r.durationMs(), r.error())).toList();
    }
    @GetMapping("/status")
    public StatusDto status() {
        RouterConfigSnapshot snap = configStore.current();
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        return new StatusDto(true, uptime, snap.routing().isTkbPayEnabled(), snap.version(), history.size(), history.capacity());
    }
}
