package com.trustgrid.api.transaction;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TransactionResponse(
        UUID transactionId,
        UUID listingId,
        TransactionType transactionType,
        UUID requesterParticipantId,
        UUID providerParticipantId,
        TransactionStatus status,
        long valueAmountCents,
        String currency,
        TransactionRiskStatus riskStatus,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        Instant cancelledAt
) {
}
