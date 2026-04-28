package com.trustgrid.api.listing;

import java.time.Instant;
import java.util.UUID;

public record EvidenceRequirementResponse(
        UUID id,
        UUID listingId,
        String evidenceType,
        boolean requiredBeforePublish,
        boolean satisfied,
        String reason,
        Instant createdAt,
        Instant updatedAt
) {
}
