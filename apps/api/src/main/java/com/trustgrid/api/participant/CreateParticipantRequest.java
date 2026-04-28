package com.trustgrid.api.participant;

import jakarta.validation.constraints.NotBlank;

public record CreateParticipantRequest(
        @NotBlank String profileSlug,
        @NotBlank String displayName,
        @NotBlank String createdBy,
        @NotBlank String reason
) {
}
