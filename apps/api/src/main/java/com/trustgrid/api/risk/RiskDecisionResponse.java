package com.trustgrid.api.risk;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RiskDecisionResponse(
        UUID riskDecisionId,
        RiskTargetType targetType,
        UUID targetId,
        int score,
        String riskLevel,
        RiskDecision decision,
        List<String> reasons,
        Map<String, Object> snapshot,
        String policyVersion,
        Instant createdAt
) {
}
