package com.trustgrid.api.participant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record VerificationUpdateRequest(
        @NotNull VerificationStatus newStatus,
        @NotBlank String actor,
        @NotBlank String reason
) {
}
