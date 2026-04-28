package com.trustgrid.api.paymentboundary;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PaymentBoundaryEventResponse(UUID eventId, UUID transactionId, String eventType, String eventKey,
                                           String reason, String requestedBy, Map<String, Object> payload,
                                           Instant createdAt) {
}
