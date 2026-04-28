package com.trustgrid.api.evidence;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CreateEvidenceRequest(
        @NotNull EvidenceTargetType targetType,
        @NotNull UUID targetId,
        UUID uploadedByParticipantId,
        @NotNull EvidenceType evidenceType,
        String objectKey,
        String evidenceHash,
        Instant capturedAt,
        Map<String, Object> metadata,
        @NotBlank String actor,
        @NotBlank String reason
) {
}
