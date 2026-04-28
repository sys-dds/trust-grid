package com.trustgrid.api.participant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AccountStatusUpdateRequest(
        @NotNull ParticipantAccountStatus newStatus,
        @NotBlank String actor,
        @NotBlank String reason
) {
}
