package com.trustgrid.api.trustcase;

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
public class TrustCaseController {
    private final TrustSafetyService service;

    public TrustCaseController(TrustSafetyService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/trust-cases")
    Map<String, Object> open(@RequestBody Map<String, Object> request) {
        return service.openCase(request);
    }

    @GetMapping("/api/v1/trust-cases")
    List<Map<String, Object>> list() {
        return service.listCases();
    }

    @GetMapping("/api/v1/trust-cases/{caseId}")
    Map<String, Object> get(@PathVariable UUID caseId) {
        return service.getCase(caseId);
    }

    @PostMapping("/api/v1/trust-cases/{caseId}/targets")
    Map<String, Object> target(@PathVariable UUID caseId, @RequestBody Map<String, Object> request) {
        return service.addCaseTarget(caseId, request);
    }

    @PostMapping("/api/v1/trust-cases/{caseId}/assign")
    Map<String, Object> assign(@PathVariable UUID caseId, @RequestBody Map<String, Object> request) {
        return service.assignCase(caseId, request);
    }

    @PostMapping("/api/v1/trust-cases/{caseId}/status")
    Map<String, Object> status(@PathVariable UUID caseId, @RequestBody Map<String, Object> request) {
        return service.updateCaseStatus(caseId, request);
    }

    @PostMapping("/api/v1/trust-cases/{caseId}/apply-playbook")
    Map<String, Object> playbook(@PathVariable UUID caseId, @RequestBody Map<String, Object> request) {
        return service.applyPlaybook(caseId, request);
    }

    @GetMapping("/api/v1/trust-cases/{caseId}/timeline")
    List<Map<String, Object>> timeline(@PathVariable UUID caseId) {
        return service.caseTimeline(caseId);
    }

    @GetMapping("/api/v1/trust-cases/{caseId}/evidence-bundle")
    Map<String, Object> bundle(@PathVariable UUID caseId) {
        return service.caseEvidenceBundle(caseId);
    }

    @GetMapping("/api/v1/trust-cases/{caseId}/recommendations")
    List<Map<String, Object>> recommendations(@PathVariable UUID caseId) {
        return service.caseRecommendations(caseId);
    }

    @PostMapping("/api/v1/trust-cases/{caseId}/recommendations/generate")
    Map<String, Object> generateRecommendations(@PathVariable UUID caseId, @RequestBody Map<String, Object> request) {
        return service.generateCaseRecommendations(caseId, request);
    }

    @PostMapping("/api/v1/trust-cases/merge")
    Map<String, Object> merge(@RequestBody Map<String, Object> request) {
        return service.mergeCases(request);
    }

    @PostMapping("/api/v1/trust-cases/{caseId}/split")
    Map<String, Object> split(@PathVariable UUID caseId, @RequestBody Map<String, Object> request) {
        return service.splitCase(caseId, request);
    }

    @PostMapping("/api/v1/trust-cases/{caseId}/replay")
    Map<String, Object> replay(@PathVariable UUID caseId) {
        return service.replayCase(caseId);
    }

    @GetMapping("/api/v1/trust-cases/metrics")
    Map<String, Object> metrics() {
        return service.caseMetrics();
    }
}
