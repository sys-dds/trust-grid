package com.trustgrid.api.policyengine;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PolicyDecisionResponse(UUID decisionId, String targetType, UUID targetId, String policyName,
                                     String policyVersion, String decision,
                                     List<Map<String, Object>> matchedRules,
                                     List<UUID> appliedExceptions,
                                     Map<String, Object> inputSnapshot,
                                     Map<String, Object> explanation,
                                     List<String> recommendedNextSteps,
                                     String deterministicRulesVersion,
                                     Instant createdAt) {
}
