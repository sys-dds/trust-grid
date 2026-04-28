package com.trustgrid.api.transaction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record NoShowRequest(
        @NotNull UUID reportedByParticipantId,
        @NotBlank String actor,
        @NotBlank String reason
) {
}
