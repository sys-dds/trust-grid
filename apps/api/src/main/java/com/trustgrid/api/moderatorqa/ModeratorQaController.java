package com.trustgrid.api.moderatorqa;

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
public class ModeratorQaController {
    private final TrustSafetyService service;

    public ModeratorQaController(TrustSafetyService service) { this.service = service; }

    @PostMapping("/api/v1/moderator-qa/reviews")
    Map<String, Object> review(@RequestBody Map<String, Object> request) { return service.createQaReview(request); }

    @GetMapping("/api/v1/moderator-qa/reviews")
    List<Map<String, Object>> reviews() { return service.qaReviews(); }

    @PostMapping("/api/v1/moderator-qa/severe-action-approvals")
    Map<String, Object> requestApproval(@RequestBody Map<String, Object> request) { return service.requestSevereApproval(request); }

    @PostMapping("/api/v1/moderator-qa/severe-action-approvals/{approvalId}/approve")
    Map<String, Object> approve(@PathVariable UUID approvalId, @RequestBody Map<String, Object> request) {
        return service.decideSevereApproval(approvalId, request, true);
    }

    @PostMapping("/api/v1/moderator-qa/severe-action-approvals/{approvalId}/reject")
    Map<String, Object> reject(@PathVariable UUID approvalId, @RequestBody Map<String, Object> request) {
        return service.decideSevereApproval(approvalId, request, false);
    }

    @PostMapping("/api/v1/moderator-qa/action-reversals")
    Map<String, Object> reverse(@RequestBody Map<String, Object> request) { return service.actionReversal(request); }

    @GetMapping("/api/v1/moderator-qa/metrics")
    Map<String, Object> metrics() { return service.qaMetrics(); }

    @PostMapping("/api/v1/trust-cases/{caseId}/quality-review")
    Map<String, Object> quality(@PathVariable UUID caseId, @RequestBody Map<String, Object> request) {
        return service.caseQualityReview(caseId, request);
    }
}
