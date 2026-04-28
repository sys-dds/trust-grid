package com.trustgrid.api.evidence;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SatisfyEvidenceRequirementRequest(
        @NotNull UUID evidenceId,
        @NotBlank String actor,
        @NotBlank String reason
) {
}
