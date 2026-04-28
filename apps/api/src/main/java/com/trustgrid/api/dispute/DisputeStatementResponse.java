package com.trustgrid.api.dispute;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DisputeStatementResponse(
        UUID statementId,
        UUID disputeId,
        UUID participantId,
        StatementType statementType,
        String statementText,
        String actor,
        String reason,
        Instant createdAt,
        Map<String, Object> metadata
) {
}
