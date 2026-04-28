package com.trustgrid.api.dispute;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ResolveDisputeRequest(
        @NotNull DisputeOutcome outcome,
        @NotBlank String resolvedBy,
        @NotBlank String reason
) {
}
