package com.trustgrid.api.participant;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TimelineEventResponse(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        UUID participantId,
        String eventKey,
        String eventType,
        String eventStatus,
        Map<String, Object> payload,
        int publishAttempts,
        Instant publishedAt,
        String lastError,
        Instant createdAt
) {
}
