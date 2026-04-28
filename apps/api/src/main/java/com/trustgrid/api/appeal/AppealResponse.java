package com.trustgrid.api.appeal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AppealResponse(UUID appealId, UUID participantId, String targetType, UUID targetId, String status,
                             String appealReason, String decision, String decidedBy, String decisionReason,
                             Instant createdAt, Instant decidedAt, Map<String, Object> metadata) {
}
