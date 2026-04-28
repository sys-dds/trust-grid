package com.trustgrid.api.reputation;

import jakarta.validation.constraints.NotBlank;

public record RecalculateReputationRequest(
        @NotBlank String actor,
        @NotBlank String reason
) {
}
