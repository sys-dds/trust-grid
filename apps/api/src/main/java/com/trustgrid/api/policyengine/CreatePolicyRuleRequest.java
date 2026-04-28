package com.trustgrid.api.policyengine;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;

public record CreatePolicyRuleRequest(UUID policyVersionId, String policyName, String policyVersion,
                                      @NotBlank String ruleKey, @NotBlank String ruleType,
                                      @NotBlank String targetScope, Map<String, Object> condition,
                                      Map<String, Object> action, Boolean enabled, Integer priority) {
}
