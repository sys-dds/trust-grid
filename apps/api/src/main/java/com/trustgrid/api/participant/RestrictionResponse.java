package com.trustgrid.api.participant;

import java.time.Instant;
import java.util.UUID;

public record RestrictionResponse(
        UUID id,
        UUID participantId,
        RestrictionType restrictionType,
        String status,
        Long maxTransactionValueCents,
        Instant createdAt,
        Instant removedAt
) {
}
