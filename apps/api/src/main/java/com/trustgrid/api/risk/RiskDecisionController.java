package com.trustgrid.api.risk;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RiskDecisionController {

    private final RiskDecisionService service;

    public RiskDecisionController(RiskDecisionService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/risk-decisions")
    public List<RiskDecisionResponse> search(@RequestParam(required = false) RiskTargetType targetType,
                                             @RequestParam(required = false) UUID targetId) {
        return service.search(targetType, targetId);
    }

    @GetMapping("/api/v1/risk-decisions/{riskDecisionId}")
    public RiskDecisionResponse get(@PathVariable UUID riskDecisionId) {
        return service.get(riskDecisionId);
    }

    @GetMapping("/api/v1/risk/explain")
    public RiskExplanationResponse explain(@RequestParam RiskTargetType targetType, @RequestParam UUID targetId) {
        return service.explain(targetType, targetId);
    }

    @PostMapping("/api/v1/transactions/{transactionId}/off-platform-contact-reports")
    public RiskDecisionResponse reportOffPlatform(@PathVariable UUID transactionId,
                                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                  @Valid @RequestBody OffPlatformContactReportRequest request) {
        return service.reportOffPlatform(transactionId, idempotencyKey, request);
    }

    @PostMapping("/api/v1/participants/{participantId}/synthetic-risk-signals")
    public RiskDecisionResponse syntheticSignal(@PathVariable UUID participantId,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                @Valid @RequestBody SyntheticRiskSignalRequest request) {
        return service.syntheticSignal(participantId, idempotencyKey, request);
    }
}
