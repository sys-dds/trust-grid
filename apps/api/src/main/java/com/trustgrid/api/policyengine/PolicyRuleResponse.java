package com.trustgrid.api.policyengine;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PolicyRuleResponse(UUID ruleId, UUID policyVersionId, String ruleKey, String ruleType,
                                 String targetScope, Map<String, Object> condition,
                                 Map<String, Object> action, boolean enabled, int priority,
                                 Instant createdAt) {
}
