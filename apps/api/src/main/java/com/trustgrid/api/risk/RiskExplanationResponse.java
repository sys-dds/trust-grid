package com.trustgrid.api.risk;

import java.util.List;
import java.util.UUID;

public record RiskExplanationResponse(
        RiskTargetType targetType,
        UUID targetId,
        String riskLevel,
        RiskDecision decision,
        List<String> matchedRules,
        List<String> nextSteps,
        String policyVersion
) {
}
