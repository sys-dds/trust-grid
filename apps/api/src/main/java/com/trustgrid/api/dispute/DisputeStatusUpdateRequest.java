package com.trustgrid.api.dispute;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DisputeStatusUpdateRequest(
        @NotNull DisputeStatus newStatus,
        @NotBlank String actor,
        @NotBlank String reason
) {
}
