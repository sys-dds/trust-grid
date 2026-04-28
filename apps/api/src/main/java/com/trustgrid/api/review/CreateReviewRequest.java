package com.trustgrid.api.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateReviewRequest(
        @NotNull UUID reviewerParticipantId,
        @NotNull UUID reviewedParticipantId,
        @Min(1) @Max(5) int overallRating,
        @Min(1) @Max(5) Integer accuracyRating,
        @Min(1) @Max(5) Integer reliabilityRating,
        @Min(1) @Max(5) Integer communicationRating,
        @Min(1) @Max(5) Integer punctualityRating,
        @Min(1) @Max(5) Integer evidenceQualityRating,
        @Min(1) @Max(5) Integer itemServiceMatchRating,
        String reviewText,
        @NotBlank String reason
) {
}
