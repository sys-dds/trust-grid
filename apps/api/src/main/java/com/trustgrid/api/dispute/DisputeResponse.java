package com.trustgrid.api.dispute;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DisputeResponse(
        UUID disputeId,
        UUID transactionId,
        UUID openedByParticipantId,
        DisputeType disputeType,
        DisputeStatus status,
        DisputeOutcome outcome,
        String reason,
        String resolutionReason,
        String resolvedBy,
        Instant openedAt,
        Instant updatedAt,
        Instant resolvedAt,
        Map<String, Object> metadata
) {
}
