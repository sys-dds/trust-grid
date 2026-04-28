package com.trustgrid.api.participant;

import java.util.List;
import java.util.UUID;

public record ParticipantTrustSummaryResponse(
        UUID participantId,
        String profileSlug,
        String displayName,
        ParticipantAccountStatus accountStatus,
        VerificationStatus verificationStatus,
        List<Capability> activeCapabilities,
        List<Capability> revokedCapabilities,
        List<Capability> restrictedCapabilities,
        List<RestrictionType> activeRestrictions,
        TrustTier trustTier,
        RiskLevel riskLevel,
        int trustScore,
        int trustConfidence,
        long maxTransactionValueCents,
        MarketplaceEligibilityResponse marketplaceEligibility
) {
}
