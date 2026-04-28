package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainPolicyControlsIntegrationTest extends Tg161To180IntegrationTestSupport {

    @Test
    void domainSpecificPolicyControlsAffectRiskDisputeReviewAndRankingDecisions() {
        UUID buyer = createCapableParticipant("domain-buyer-" + suffix(), "Domain Buyer", "BUY");
        UUID seller = createCapableParticipant("domain-seller-" + suffix(), "Domain Seller", "SELL_ITEMS", "OFFER_SERVICES");
        UUID listing = createListing(seller, "ITEM_LISTING", "ELECTRONICS", "domain electronics " + suffix(), 180000L, null, itemDetails(true));
        Flow transactionFlow = createCompletedServiceFlowBetween(buyer, seller, "domain-tx");
        UUID transaction = transactionFlow.transactionId();
        Flow disputeFlow = createDisputableServiceFlow("domain-dispute");
        UUID dispute = openDispute(disputeFlow, "domain-dispute-" + suffix());

        UUID policy = createPolicy("risk_policy", "domain_controls_v1", true);
        addRule("risk_policy", "domain_controls_v1", "category-electronics-verification", "RISK_RULE", "LISTING",
                condition("categoryCode", "equals", "ELECTRONICS"), decision("REQUIRE_VERIFICATION"), 5);
        addRule("risk_policy", "domain_controls_v1", "high-value-manual-review", "RISK_RULE", "TRANSACTION",
                condition("valueCents", "greater_than_or_equal", 100000), decision("REQUIRE_MANUAL_REVIEW"), 10);
        addRule("risk_policy", "domain_controls_v1", "new-user-value-limit", "RISK_RULE", "TRANSACTION",
                condition("trustTier", "equals", "NEW"), decision("BLOCK_TRANSACTION"), 20);
        addRule("risk_policy", "domain_controls_v1", "dispute-evidence-required", "DISPUTE_RULE", "DISPUTE",
                condition("unsatisfiedEvidenceCount", "greater_than", 0), decision("REQUIRE_EXTRA_EVIDENCE"), 5);
        addRule("risk_policy", "domain_controls_v1", "review-cluster-zero-weight", "REVIEW_WEIGHT_RULE", "REVIEW",
                condition("suppressionCount", "greater_than_or_equal", 0), decision("SUPPRESS_REVIEW_WEIGHT"), 5);
        addRule("risk_policy", "domain_controls_v1", "ranking-risk-penalty", "RANKING_RULE", "LISTING",
                condition("riskTier", "in", java.util.List.of("HIGH", "RESTRICTED")), Map.of("decision", "ALLOW_WITH_LIMITS", "riskPenalty", -25), 50);
        approveAndActivate(policy);

        assertThat(evaluatePolicy("risk_policy", "domain_controls_v1", "LISTING", listing,
                Map.of("categoryCode", "ELECTRONICS", "riskTier", "HIGH")).get("decision")).isEqualTo("REQUIRE_VERIFICATION");
        assertThat(evaluatePolicy("risk_policy", "domain_controls_v1", "TRANSACTION", transaction,
                Map.of("valueCents", 180000, "trustTier", "NEW")).get("decision")).isEqualTo("REQUIRE_MANUAL_REVIEW");
        assertThat(evaluatePolicy("risk_policy", "domain_controls_v1", "DISPUTE", dispute, Map.of()).get("decision")).isEqualTo("REQUIRE_EXTRA_EVIDENCE");

        Flow reviewFlow = createCompletedServiceFlowBetween(buyer, seller, "domain-review");
        UUID reviewId = review(reviewFlow.transactionId(), buyer, seller, 5, "Domain review", "domain-review-" + suffix());
        assertThat(evaluatePolicy("risk_policy", "domain_controls_v1", "REVIEW", reviewId, Map.of("suppressionCount", 1)).get("decision"))
                .isEqualTo("SUPPRESS_REVIEW_WEIGHT");

        get("/api/v1/listings/trust-ranked-search?query=domain&policyVersion=trust_balanced_v1");
        Map<?, ?> rankingSim = post("/api/v1/policy-simulations/counterfactual-ranking", Map.of(
                "policyName", "ranking_policy", "toPolicyVersion", "domain_controls_v1",
                "requestedBy", "operator@example.com", "reason", "Ranking controls"), null).getBody();
        assertThat(rankingSim.toString()).contains("candidateSnapshotsEvaluated", "dataDriven");
    }
}
