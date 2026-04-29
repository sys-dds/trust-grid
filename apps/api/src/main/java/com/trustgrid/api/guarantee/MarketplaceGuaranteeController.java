package com.trustgrid.api.guarantee;

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
public class MarketplaceGuaranteeController {
    private final TrustSafetyService service;

    public MarketplaceGuaranteeController(TrustSafetyService service) { this.service = service; }

    @PostMapping("/api/v1/marketplace-guarantees/policies")
    Map<String, Object> policy(@RequestBody Map<String, Object> request) { return service.createGuaranteePolicy(request); }

    @GetMapping("/api/v1/marketplace-guarantees/policies")
    List<Map<String, Object>> policies() { return service.guaranteePolicies(); }

    @PostMapping("/api/v1/marketplace-guarantees/eligibility-simulate")
    Map<String, Object> simulate(@RequestBody Map<String, Object> request) { return service.guaranteeEligibility(request); }

    @GetMapping("/api/v1/marketplace-guarantees/decisions/{decisionId}")
    Map<String, Object> decision(@PathVariable UUID decisionId) { return service.guaranteeDecision(decisionId); }

    @GetMapping("/api/v1/marketplace-guarantees/decisions/{decisionId}/timeline")
    List<Map<String, Object>> timeline(@PathVariable UUID decisionId) { return service.guaranteeTimeline(decisionId); }

    @PostMapping("/api/v1/marketplace-guarantees/decisions/{decisionId}/recommend-payment-boundary")
    Map<String, Object> recommend(@PathVariable UUID decisionId, @RequestBody Map<String, Object> request) {
        return service.guaranteePaymentBoundary(decisionId, request);
    }
}
