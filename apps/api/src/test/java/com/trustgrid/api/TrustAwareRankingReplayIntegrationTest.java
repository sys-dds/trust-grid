package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustAwareRankingReplayIntegrationTest extends EvidenceDisputeRiskIntegrationTestSupport {

    @Test
    void trustAwareRankingReturnsReasonsSuppressesHiddenListingsAndReplaysDeterministically() {
        UUID buyer = createCapableParticipant("ranking-buyer-" + suffix(), "Ranking Buyer", "BUY");
        assertThat(buyer).isNotNull();
        UUID provider = createCapableParticipant("ranking-provider-" + suffix(), "Ranking Provider", "OFFER_SERVICES");
        UUID listing = createListing(provider, "SERVICE_OFFER", "TUTORING", "Ranking Java tutoring " + suffix(), 2500L, null, serviceDetails());
        publish(listing);

        Map<?, ?> ranked = get("/api/v1/listings/trust-ranked-search?query=Ranking&policyVersion=trust_balanced_v1").getBody();
        assertThat(ranked.get("rankingDecisionId")).isNotNull();
        assertThat(ranked.get("results").toString()).contains("category_match");

        post("/api/v1/listings/" + listing + "/moderation/hide", Map.of(
                "actor", "moderator@example.com",
                "reason", "Temporary moderation"
        ), "ranking-hide-" + suffix());
        Map<?, ?> hiddenSearch = get("/api/v1/listings/trust-ranked-search?query=Ranking&policyVersion=risk_averse_v1").getBody();
        assertThat(hiddenSearch.get("results").toString()).doesNotContain(listing.toString());

        UUID rankingDecisionId = UUID.fromString((String) ranked.get("rankingDecisionId"));
        Map<?, ?> replay = post("/api/v1/listings/ranking-decisions/" + rankingDecisionId + "/replay", Map.of(), null).getBody();
        assertThat(replay.get("matched")).isEqualTo(true);
        assertThat(replay.get("policyVersion")).isEqualTo("trust_balanced_v1");
    }
}
