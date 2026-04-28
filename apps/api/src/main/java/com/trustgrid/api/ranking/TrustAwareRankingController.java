package com.trustgrid.api.ranking;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrustAwareRankingController {

    private final TrustAwareRankingService service;

    public TrustAwareRankingController(TrustAwareRankingService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/listings/trust-ranked-search")
    public RankingSearchResponse search(@RequestParam(required = false) String query,
                                        @RequestParam(required = false) String listingType,
                                        @RequestParam(required = false) String categoryCode,
                                        @RequestParam(required = false) String locationMode,
                                        @RequestParam(required = false) Long minPriceCents,
                                        @RequestParam(required = false) Long maxPriceCents,
                                        @RequestParam(required = false) RankingPolicyVersion policyVersion,
                                        @RequestParam(required = false) Integer limit,
                                        @RequestParam(required = false) Integer offset) {
        return service.search(query, listingType, categoryCode, locationMode, minPriceCents, maxPriceCents,
                policyVersion, limit, offset);
    }

    @PostMapping("/api/v1/listings/ranking-decisions/{rankingDecisionId}/replay")
    public RankingReplayResponse replay(@PathVariable UUID rankingDecisionId) {
        return service.replay(rankingDecisionId);
    }
}
