package com.trustgrid.api.participant;

import java.time.Instant;
import java.util.UUID;

public record CapabilityResponse(
        UUID id,
        UUID participantId,
        Capability capability,
        CapabilityStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
