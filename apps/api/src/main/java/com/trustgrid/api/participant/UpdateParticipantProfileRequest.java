package com.trustgrid.api.participant;

import jakarta.validation.constraints.NotBlank;

public record UpdateParticipantProfileRequest(
        @NotBlank String displayName,
        String bio,
        String locationSummary,
        String capabilityDescription,
        String profilePhotoObjectKey,
        @NotBlank String updatedBy,
        @NotBlank String reason
) {
}
