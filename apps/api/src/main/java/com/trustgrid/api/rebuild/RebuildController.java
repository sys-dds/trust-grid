package com.trustgrid.api.rebuild;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RebuildController {
    private final RebuildService service;

    public RebuildController(RebuildService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/rebuilds/reputation")
    public Map<String, Object> reputation(@RequestBody Map<String, Object> request) {
        return service.reputation(request);
    }

    @PostMapping("/api/v1/rebuilds/search-index")
    public Map<String, Object> searchIndex(@RequestBody Map<String, Object> request) {
        return service.searchIndex(request);
    }

    @PostMapping("/api/v1/consistency/evidence/verify")
    public Map<String, Object> evidence(@RequestBody Map<String, Object> request) {
        return service.evidence(request);
    }

    @PostMapping("/api/v1/replay/outbox")
    public Map<String, Object> outbox(@RequestBody Map<String, Object> request) {
        return service.outbox(request);
    }

    @PostMapping("/api/v1/replay/audit-timeline")
    public Map<String, Object> timeline(@RequestBody Map<String, Object> request) {
        return service.timeline(request);
    }

    @GetMapping("/api/v1/rebuilds/{rebuildRunId}")
    public Map<String, Object> run(@PathVariable UUID rebuildRunId) {
        return service.run(rebuildRunId);
    }

    @GetMapping("/api/v1/consistency/findings")
    public List<Map<String, Object>> findings() {
        return service.findings();
    }
}
