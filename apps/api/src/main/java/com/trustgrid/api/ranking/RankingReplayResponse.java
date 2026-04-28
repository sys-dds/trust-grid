package com.trustgrid.api.ranking;

import java.util.List;
import java.util.UUID;

public record RankingReplayResponse(
        UUID rankingDecisionId,
        List<UUID> originalResultIds,
        List<UUID> replayedResultIds,
        boolean matched,
        RankingPolicyVersion policyVersion,
        String reasonsSummary
) {
}
