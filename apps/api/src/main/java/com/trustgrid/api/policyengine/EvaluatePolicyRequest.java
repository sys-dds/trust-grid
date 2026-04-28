package com.trustgrid.api.policyengine;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

public record EvaluatePolicyRequest(@NotBlank String policyName, @NotBlank String policyVersion,
                                    @NotBlank String targetType, @NotNull UUID targetId,
                                    Map<String, Object> input) {
}
