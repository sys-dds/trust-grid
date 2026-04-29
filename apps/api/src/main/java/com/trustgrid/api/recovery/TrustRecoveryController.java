package com.trustgrid.api.recovery;

import com.trustgrid.api.trustsafety.TrustSafetyService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrustRecoveryController {
    private final TrustSafetyService service;

    public TrustRecoveryController(TrustSafetyService service) { this.service = service; }

    @PostMapping("/api/v1/trust-recovery/plans")
    Map<String, Object> create(@RequestBody Map<String, Object> request) { return service.createRecoveryPlan(request); }

    @GetMapping("/api/v1/trust-recovery/plans")
    List<Map<String, Object>> list() { return service.recoveryPlans(); }

    @GetMapping("/api/v1/trust-recovery/plans/{planId}")
    Map<String, Object> get(@PathVariable UUID planId) { return service.recoveryPlan(planId); }

    @PostMapping("/api/v1/trust-recovery/plans/{planId}/evaluate")
    Map<String, Object> evaluate(@PathVariable UUID planId, @RequestBody Map<String, Object> request) {
        return service.evaluateRecovery(planId, request);
    }

    @PostMapping("/api/v1/trust-recovery/plans/{planId}/milestones")
    Map<String, Object> milestone(@PathVariable UUID planId, @RequestBody Map<String, Object> request) {
        return service.recoveryMilestone(planId, request);
    }

    @PostMapping("/api/v1/trust-recovery/plans/{planId}/recommend-capability-restoration")
    Map<String, Object> recommend(@PathVariable UUID planId, @RequestBody Map<String, Object> request) {
        return service.recommendRestoration(planId, request);
    }
}
