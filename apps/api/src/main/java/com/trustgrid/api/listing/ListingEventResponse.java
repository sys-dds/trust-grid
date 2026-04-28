package com.trustgrid.api.listing;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ListingEventResponse(
        UUID id,
        String eventKey,
        String eventType,
        String eventStatus,
        int publishAttempts,
        Instant publishedAt,
        Map<String, Object> payload,
        Instant createdAt
) {
}
