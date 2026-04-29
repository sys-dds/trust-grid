package com.trustgrid.api.adversarial;

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
public class AdversarialSimulationController {
    private final TrustSafetyService service;

    public AdversarialSimulationController(TrustSafetyService service) { this.service = service; }

    @PostMapping("/api/v1/adversarial/scenarios")
    Map<String, Object> scenario(@RequestBody Map<String, Object> request) { return service.createScenario(request); }

    @GetMapping("/api/v1/adversarial/scenarios")
    List<Map<String, Object>> scenarios() { return service.scenarios(); }

    @PostMapping("/api/v1/adversarial/attack-runs")
    Map<String, Object> run(@RequestBody Map<String, Object> request) { return service.createAttackRun(request); }

    @GetMapping("/api/v1/adversarial/attack-runs")
    List<Map<String, Object>> runs() { return service.attackRuns(); }

    @GetMapping("/api/v1/adversarial/attack-runs/{attackRunId}")
    Map<String, Object> run(@PathVariable UUID attackRunId) { return service.attackRun(attackRunId); }

    @PostMapping("/api/v1/adversarial/attack-runs/{attackRunId}/replay")
    Map<String, Object> replay(@PathVariable UUID attackRunId) { return service.replayAttackRun(attackRunId); }

    @GetMapping("/api/v1/adversarial/attack-runs/{attackRunId}/coverage")
    List<Map<String, Object>> coverage(@PathVariable UUID attackRunId) { return service.attackCoverage(attackRunId); }

    @GetMapping("/api/v1/adversarial/attack-runs/{attackRunId}/defense-recommendations")
    List<Map<String, Object>> recommendations(@PathVariable UUID attackRunId) { return service.defenseRecommendations(attackRunId); }

    @GetMapping("/api/v1/adversarial/attack-runs/{attackRunId}/evidence-bundle")
    Map<String, Object> bundle(@PathVariable UUID attackRunId) { return service.attackEvidenceBundle(attackRunId); }

    @PostMapping("/api/v1/adversarial/false-positive-reviews")
    Map<String, Object> falsePositive(@RequestBody Map<String, Object> request) { return service.falsePositive(request); }

    @PostMapping("/api/v1/adversarial/false-positive-reviews/{reviewId}/decide")
    Map<String, Object> decide(@PathVariable UUID reviewId, @RequestBody Map<String, Object> request) {
        return service.decideFalsePositive(reviewId, request);
    }

    @GetMapping("/api/v1/adversarial/coverage-dashboard")
    Map<String, Object> dashboard() { return service.coverageDashboard(); }
}
