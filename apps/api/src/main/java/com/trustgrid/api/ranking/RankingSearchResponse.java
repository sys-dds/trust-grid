package com.trustgrid.api.ranking;

import java.util.List;
import java.util.UUID;

public record RankingSearchResponse(
        UUID rankingDecisionId,
        RankingPolicyVersion policyVersion,
        List<RankingListingResponse> results,
        int limit,
        int offset
) {
}
