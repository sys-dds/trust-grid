package com.trustgrid.api.review;

import java.time.Instant;
import java.util.UUID;

public record ReviewResponse(
        UUID reviewId,
        UUID transactionId,
        UUID reviewerParticipantId,
        UUID reviewedParticipantId,
        String status,
        int overallRating,
        Integer accuracyRating,
        Integer reliabilityRating,
        Integer communicationRating,
        Integer punctualityRating,
        Integer evidenceQualityRating,
        Integer itemServiceMatchRating,
        String reviewText,
        int confidenceWeight,
        String suppressionReason,
        Instant createdAt
) {
}
