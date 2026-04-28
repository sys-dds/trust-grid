package com.trustgrid.api.evidence;

import java.time.Instant;
import java.util.UUID;

public record EvidenceRequirementResponse(
        UUID requirementId,
        EvidenceTargetType targetType,
        UUID targetId,
        EvidenceType evidenceType,
        String requiredBeforeAction,
        boolean satisfied,
        UUID satisfiedByEvidenceId,
        String reason,
        Instant createdAt,
        Instant updatedAt
) {
}
