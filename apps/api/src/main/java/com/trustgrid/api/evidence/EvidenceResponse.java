package com.trustgrid.api.evidence;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EvidenceResponse(
        UUID evidenceId,
        EvidenceTargetType targetType,
        UUID targetId,
        UUID uploadedByParticipantId,
        EvidenceType evidenceType,
        String objectKey,
        String evidenceHash,
        Instant capturedAt,
        Map<String, Object> metadata,
        Instant createdAt
) {
}
