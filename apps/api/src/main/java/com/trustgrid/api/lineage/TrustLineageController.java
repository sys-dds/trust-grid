package com.trustgrid.api.lineage;

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
public class TrustLineageController {
    private final TrustLineageService service;

    public TrustLineageController(TrustLineageService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/participants/{participantId}/trust-score/explanation")
    public Map<String, Object> trustExplanation(@PathVariable UUID participantId) {
        return service.trustExplanation(participantId);
    }

    @GetMapping("/api/v1/participants/{participantId}/trust-score/lineage")
    public List<Map<String, Object>> trustLineage(@PathVariable UUID participantId) {
        return service.trustLineage(participantId);
    }

    @PostMapping("/api/v1/participants/{participantId}/trust-score/lineage/rebuild")
    public Map<String, Object> rebuildParticipantLineage(@PathVariable UUID participantId,
                                                         @RequestBody Map<String, Object> request) {
        return service.rebuild("TRUST_SCORE_LINEAGE", request);
    }

    @GetMapping("/api/v1/listings/{listingId}/ranking-lineage")
    public List<Map<String, Object>> rankingLineage(@PathVariable UUID listingId) {
        return service.rankingLineage(listingId);
    }

    @GetMapping("/api/v1/policy-lineage")
    public List<Map<String, Object>> policyLineage(@RequestParam(required = false) String policyName) {
        return service.policyLineage(policyName);
    }

    @PostMapping("/api/v1/lineage/rebuild/trust-score")
    public Map<String, Object> rebuildTrust(@RequestBody Map<String, Object> request) {
        return service.rebuild("TRUST_SCORE_LINEAGE", request);
    }

    @PostMapping("/api/v1/lineage/rebuild/ranking")
    public Map<String, Object> rebuildRanking(@RequestBody Map<String, Object> request) {
        return service.rebuild("RANKING_LINEAGE", request);
    }

    @PostMapping("/api/v1/lineage/rebuild/policy")
    public Map<String, Object> rebuildPolicy(@RequestBody Map<String, Object> request) {
        return service.rebuild("POLICY_LINEAGE", request);
    }

    @PostMapping("/api/v1/lineage/rebuild/full")
    public Map<String, Object> rebuildFull(@RequestBody Map<String, Object> request) {
        return service.rebuild("FULL_LINEAGE", request);
    }

    @GetMapping("/api/v1/lineage/rebuild-runs")
    public List<Map<String, Object>> rebuildRuns() {
        return service.rebuildRuns();
    }
}
