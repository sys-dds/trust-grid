package com.trustgrid.api.participant;

import java.time.Instant;
import java.util.UUID;

public record ParticipantResponse(
        UUID participantId,
        String profileSlug,
        String displayName,
        ParticipantAccountStatus accountStatus,
        VerificationStatus verificationStatus,
        TrustTier trustTier,
        RiskLevel riskLevel,
        String bio,
        String locationSummary,
        String capabilityDescription,
        String profilePhotoObjectKey,
        int profileCompletenessScore,
        Instant createdAt,
        Instant updatedAt
) {
}
