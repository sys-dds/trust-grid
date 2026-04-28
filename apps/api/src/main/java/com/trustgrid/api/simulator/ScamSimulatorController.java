package com.trustgrid.api.simulator;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScamSimulatorController {
    private final ScamSimulatorService service;

    public ScamSimulatorController(ScamSimulatorService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/simulators/scam/v1")
    public Map<String, Object> v1(@RequestBody Map<String, Object> request) {
        return service.scam("v1", request);
    }

    @PostMapping("/api/v1/simulators/scam/v2")
    public Map<String, Object> v2(@RequestBody Map<String, Object> request) {
        return service.scam("v2", request);
    }

    @PostMapping("/api/v1/simulators/seed-scale")
    public Map<String, Object> seedScale(@RequestBody Map<String, Object> request) {
        return service.seedScale(request);
    }

    @PostMapping("/api/v1/benchmarks/run")
    public Map<String, Object> benchmark(@RequestBody Map<String, Object> request) {
        return service.benchmark(request);
    }
}
