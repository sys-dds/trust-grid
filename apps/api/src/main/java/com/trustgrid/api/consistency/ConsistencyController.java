package com.trustgrid.api.consistency;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConsistencyController {
    private final ConsistencyService service;

    public ConsistencyController(ConsistencyService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/consistency/checks/trust-profile")
    public Map<String, Object> trustProfile(@RequestBody Map<String, Object> request) {
        return service.run("TRUST_PROFILE", request);
    }

    @PostMapping("/api/v1/consistency/checks/reputation-rebuild")
    public Map<String, Object> reputation(@RequestBody Map<String, Object> request) {
        return service.run("REPUTATION_REBUILD", request);
    }

    @PostMapping("/api/v1/consistency/checks/search-index")
    public Map<String, Object> search(@RequestBody Map<String, Object> request) {
        return service.run("SEARCH_INDEX", request);
    }

    @PostMapping("/api/v1/consistency/checks/event-analytics")
    public Map<String, Object> analytics(@RequestBody Map<String, Object> request) {
        return service.run("EVENT_ANALYTICS", request);
    }

    @PostMapping("/api/v1/consistency/checks/evidence-reference")
    public Map<String, Object> evidence(@RequestBody Map<String, Object> request) {
        return service.run("EVIDENCE_REFERENCE", request);
    }

    @PostMapping("/api/v1/consistency/checks/dispute")
    public Map<String, Object> dispute(@RequestBody Map<String, Object> request) {
        return service.run("DISPUTE", request);
    }

    @PostMapping("/api/v1/consistency/checks/capability")
    public Map<String, Object> capability(@RequestBody Map<String, Object> request) {
        return service.run("CAPABILITY", request);
    }

    @PostMapping("/api/v1/consistency/checks/full")
    public Map<String, Object> full(@RequestBody Map<String, Object> request) {
        return service.run("FULL_CONSISTENCY", request);
    }

    @GetMapping("/api/v1/consistency/check-runs")
    public List<Map<String, Object>> runs() {
        return service.runs();
    }

}
