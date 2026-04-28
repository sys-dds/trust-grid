package com.trustgrid.api.participant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CapabilityMutationRequest(
        @NotNull Capability capability,
        @NotBlank String actor,
        @NotBlank String reason
) {
}
