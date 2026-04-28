package com.trustgrid.api.ranking;

import java.util.List;
import java.util.UUID;

public record RankingListingResponse(
        UUID listingId,
        UUID ownerParticipantId,
        String listingType,
        String categoryCode,
        String title,
        String locationMode,
        long score,
        List<String> reasons
) {
}
