package com.trustgrid.api.dispute;

import java.time.Instant;
import java.util.UUID;

public record DisputeEvidenceDeadlineResponse(
        UUID deadlineId,
        UUID disputeId,
        String requiredFromRole,
        Instant dueAt,
        String status,
        Instant createdAt,
        Instant satisfiedAt,
        Instant missedAt
) {
}
