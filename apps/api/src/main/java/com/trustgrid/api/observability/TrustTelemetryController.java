package com.trustgrid.api.observability;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrustTelemetryController {
    private final TrustTelemetryService service;

    public TrustTelemetryController(TrustTelemetryService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/trust-telemetry/record")
    public Map<String, Object> record(@RequestBody Map<String, Object> request) {
        return service.record(request);
    }

    @GetMapping("/api/v1/trust-telemetry")
    public List<Map<String, Object>> telemetry(@RequestParam(required = false) String type) {
        return service.telemetry(type);
    }

    @PostMapping("/api/v1/trust-slos")
    public Map<String, Object> createSlo(@RequestBody Map<String, Object> request) {
        return service.createSlo(request);
    }

    @GetMapping("/api/v1/trust-slos")
    public List<Map<String, Object>> slos() {
        return service.slos();
    }

    @PostMapping("/api/v1/trust-slos/evaluate")
    public Map<String, Object> evaluateSlos() {
        return service.evaluateSlos();
    }

    @PostMapping("/api/v1/trust-monitors/run")
    public Map<String, Object> runMonitors(@RequestBody Map<String, Object> request) {
        return service.runMonitors(request);
    }

    @GetMapping("/api/v1/trust-alerts")
    public List<Map<String, Object>> alerts() {
        return service.alerts();
    }

    @PostMapping("/api/v1/trust-alerts/{alertId}/acknowledge")
    public Map<String, Object> acknowledge(@PathVariable UUID alertId, @RequestBody Map<String, Object> request) {
        return service.acknowledgeAlert(alertId, request);
    }

    @GetMapping("/api/v1/ops/dashboard/trust-control-room")
    public Map<String, Object> dashboard() {
        return service.dashboard();
    }
}
