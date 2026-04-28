package com.trustgrid.api.category;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CategoryResponse(
        UUID id,
        String code,
        String name,
        String description,
        CategoryRiskTier defaultRiskTier,
        List<ListingType> allowedListingTypes,
        String evidenceRequirementHint,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
