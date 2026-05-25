package ru.tkbbank.sbprouter.management;

import org.springframework.web.bind.annotation.*;
import ru.tkbbank.sbprouter.management.dto.*;

@RestController
@RequestMapping("/api/admin/config")
public class AdminConfigController {
    private final ConfigStore store;
    private final ConfigService service;
    private final ConfigDtoMapper mapper;
    public AdminConfigController(ConfigStore store, ConfigService service, ConfigDtoMapper mapper) {
        this.store = store; this.service = service; this.mapper = mapper;
    }
    @GetMapping
    public ConfigSnapshotDto get() { return mapper.toSnapshotDto(store.current()); }
    @PutMapping("/routing")
    public ConfigSnapshotDto putRouting(@RequestParam long expectedVersion, @RequestBody RoutingConfigDto dto) {
        return mapper.toSnapshotDto(service.updateRouting(mapper.toRouting(dto), expectedVersion));
    }
    @PutMapping("/terminals")
    public ConfigSnapshotDto putTerminals(@RequestParam long expectedVersion, @RequestBody TerminalsConfigDto dto) {
        return mapper.toSnapshotDto(service.updateTerminals(mapper.toTerminals(dto), expectedVersion));
    }
    @PutMapping("/upstreams")
    public ConfigSnapshotDto putUpstreams(@RequestParam long expectedVersion, @RequestBody UpstreamsConfigDto dto) {
        return mapper.toSnapshotDto(service.updateUpstreams(mapper.toUpstreams(dto), expectedVersion));
    }
    @PutMapping("/extraction-rules")
    public ConfigSnapshotDto putExtractionRules(@RequestParam long expectedVersion, @RequestBody ExtractionRulesConfigDto dto) {
        return mapper.toSnapshotDto(service.updateExtractionRules(mapper.toExtractionRules(dto), expectedVersion));
    }
}
