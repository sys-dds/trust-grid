package com.trustgrid.api.incident;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrustIncidentController {
    private final TrustIncidentService service;

    public TrustIncidentController(TrustIncidentService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/trust-incidents")
    public Map<String, Object> create(@RequestBody Map<String, Object> request) {
        return service.create(request);
    }

    @PostMapping("/api/v1/trust-incidents/{incidentId}/status")
    public Map<String, Object> status(@PathVariable UUID incidentId, @RequestBody Map<String, Object> request) {
        return service.status(incidentId, request);
    }

    @GetMapping("/api/v1/trust-incidents")
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @GetMapping("/api/v1/trust-incidents/{incidentId}")
    public Map<String, Object> get(@PathVariable UUID incidentId) {
        return service.get(incidentId);
    }

    @GetMapping("/api/v1/trust-incidents/{incidentId}/timeline")
    public List<Map<String, Object>> timeline(@PathVariable UUID incidentId) {
        return service.timeline(incidentId);
    }

    @GetMapping("/api/v1/trust-incidents/{incidentId}/impact")
    public Map<String, Object> impact(@PathVariable UUID incidentId) {
        return service.impact(incidentId);
    }

    @GetMapping("/api/v1/trust-incidents/{incidentId}/evidence-bundle")
    public Map<String, Object> evidenceBundle(@PathVariable UUID incidentId) {
        return service.evidenceBundle(incidentId);
    }

    @GetMapping("/api/v1/trust-incidents/metrics")
    public Map<String, Object> metrics() {
        return service.metrics();
    }

    @PostMapping("/api/v1/trust-incidents/{incidentId}/replay")
    public Map<String, Object> replay(@PathVariable UUID incidentId) {
        return service.replay(incidentId);
    }
}
