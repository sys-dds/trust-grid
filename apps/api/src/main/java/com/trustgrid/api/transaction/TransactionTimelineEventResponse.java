package com.trustgrid.api.transaction;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TransactionTimelineEventResponse(
        UUID id,
        UUID transactionId,
        String eventType,
        UUID actorParticipantId,
        String actor,
        String reason,
        Map<String, Object> payload,
        Instant createdAt
) {
}
