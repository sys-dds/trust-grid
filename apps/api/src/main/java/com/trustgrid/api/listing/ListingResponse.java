package com.trustgrid.api.listing;

import com.trustgrid.api.category.ListingType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ListingResponse(
        UUID listingId,
        UUID ownerParticipantId,
        ListingType listingType,
        String categoryCode,
        String title,
        String description,
        Long priceAmountCents,
        Long budgetAmountCents,
        String currency,
        LocationMode locationMode,
        ListingStatus status,
        ListingRiskTier riskTier,
        ListingModerationStatus moderationStatus,
        boolean singleAccept,
        int revision,
        Map<String, Object> details,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt
) {
}
