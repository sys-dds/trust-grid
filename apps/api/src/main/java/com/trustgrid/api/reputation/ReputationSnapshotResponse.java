package com.trustgrid.api.reputation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ReputationSnapshotResponse(
        UUID participantId,
        int trustScore,
        int trustConfidence,
        String trustTier,
        String riskLevel,
        List<String> strengths,
        List<String> penalties,
        List<String> recentChanges,
        Map<String, Object> reviewConfidenceSummary,
        Instant latestSnapshotAt
) {
}
