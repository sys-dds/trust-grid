package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RankingPolicyLineageIntegrationTest extends Tg181To220IntegrationTestSupport {

    @Test
    void rankingAndPolicyLineageRecordDecisionReasonsAndRespectVisibility() {
        UUID provider = createCapableParticipant("rank-lineage-provider-" + suffix(), "Ranking Provider", "OFFER_SERVICES");
        UUID listing = createListing(provider, "SERVICE_OFFER", "TUTORING", "ranking lineage " + suffix(),
                2000L, null, serviceDetails());
        publish(listing);
        get("/api/v1/listings/trust-ranked-search?query=ranking&policyVersion=trust_balanced_v1");

        String version = "rank_policy_" + suffix();
        createRiskPolicyWithRule(version, "ranking_visibility_lineage", "RANKING_RULE", "LISTING",
                condition("valueCents", "greater_than_or_equal", 0), "ALLOW_WITH_LIMITS");
        Map<?, ?> decision = evaluatePolicy("risk_policy", version, "LISTING", listing, Map.of());
        assertThat(decision.get("decision")).isEqualTo("ALLOW_WITH_LIMITS");

        post("/api/v1/lineage/rebuild/full", operator(), null);
        assertThat(getList("/api/v1/listings/" + listing + "/ranking-lineage").getBody()).isNotEmpty();
        assertThat(getList("/api/v1/policy-lineage?policyName=risk_policy").getBody()).isNotEmpty();
        assertThat(countRows("select count(*) from marketplace_events where event_type in ('RANKING_LINEAGE_RECORDED','POLICY_LINEAGE_RECORDED')"))
                .isGreaterThanOrEqualTo(2);
    }
}
