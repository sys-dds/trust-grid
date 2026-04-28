package com.trustgrid.api.paymentboundary;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PaymentBoundaryStateResponse(UUID stateId, UUID transactionId, String state, String reason,
                                           String createdBy, Instant createdAt, Map<String, Object> metadata) {
}
