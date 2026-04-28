package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class TrustGrid161To180PolicyEngineE2EIntegrationTest extends Tg161To180IntegrationTestSupport {

    @Test
    void policyEngineApprovalsExceptionsSimulationAndRollbackWorkAcrossMarketplaceData() {
        UUID newSeller = createCapableParticipant("e2e-new-seller-" + suffix(), "E2E New Seller", "SELL_ITEMS");
        UUID verifiedSeller = createCapableParticipant("e2e-verified-seller-" + suffix(), "E2E Verified Seller", "SELL_ITEMS", "OFFER_SERVICES");
        UUID buyer = createCapableParticipant("e2e-buyer-" + suffix(), "E2E Buyer", "BUY");
        UUID listing = createListing(newSeller, "ITEM_LISTING", "ELECTRONICS", "e2e policy laptop " + suffix(), 200000L, null, itemDetails(true));
        publish(listing);
        UUID otherListing = createListing(verifiedSeller, "ITEM_LISTING", "ELECTRONICS", "e2e policy camera " + suffix(), 200000L, null, itemDetails(true));
        publish(otherListing);
        UUID transaction = createCompletedServiceFlowBetween(buyer, verifiedSeller, "e2e-policy-tx").transactionId();

        ResponseEntity<Map> lowRiskHold = post("/api/v1/transactions/" + transaction
                + "/payment-boundary/request-payout-hold", actorReason(), null);
        assertThat(lowRiskHold.getStatusCode().value()).isEqualTo(409);
        ResponseEntity<Map> badRefund = post("/api/v1/transactions/" + transaction
                + "/payment-boundary/request-refund", actorReason(), null);
        assertThat(badRefund.getStatusCode().value()).isEqualTo(409);

        post("/api/v1/ops/moderator-actions/restrict-capability", Map.of(
                "targetType", "PARTICIPANT", "targetId", newSeller.toString(), "participantId", newSeller.toString(),
                "capability", "SELL_ITEMS", "actor", "moderator@example.com", "reason", "Policy E2E restrict"), null);
        assertThat(capabilityStatus(newSeller, "SELL_ITEMS")).isEqualTo("RESTRICTED");
        UUID capabilityId = jdbcTemplate.queryForObject("""
                select id from participant_capabilities where participant_id = ? and capability = 'SELL_ITEMS'
                """, UUID.class, newSeller);
        Map<?, ?> appeal = post("/api/v1/participants/" + newSeller + "/appeals", Map.of(
                "targetType", "PARTICIPANT_CAPABILITY", "targetId", capabilityId.toString(),
                "appealReason", "Restore specific capability", "metadata", Map.of()), null).getBody();
        post("/api/v1/appeals/" + appeal.get("appealId") + "/decide", Map.of(
                "decision", "CAPABILITY_RESTORED", "decidedBy", "moderator@example.com",
                "reason", "Targeted restore"), null);
        assertThat(capabilityStatus(newSeller, "SELL_ITEMS")).isEqualTo("ACTIVE");

        UUID baseline = createPolicy("risk_policy", "e2e_baseline_v1", true);
        addRule("risk_policy", "e2e_baseline_v1", "baseline-review", "RISK_RULE", "LISTING",
                condition("valueCents", "greater_than", 500000), decision("REQUIRE_MANUAL_REVIEW"), 10);
        approveAndActivate(baseline);
        UUID strict = createPolicy("risk_policy", "e2e_strict_v2", true);
        addRule("risk_policy", "e2e_strict_v2", "category-electronics", "RISK_RULE", "LISTING",
                condition("categoryCode", "equals", "ELECTRONICS"), decision("REQUIRE_VERIFICATION"), 5);
        addRule("risk_policy", "e2e_strict_v2", "high-value-block", "RISK_RULE", "LISTING",
                condition("valueCents", "greater_than", 100000), decision("BLOCK_TRANSACTION"), 10);
        addRule("risk_policy", "e2e_strict_v2", "new-user-limit", "RISK_RULE", "TRANSACTION",
                condition("trustTier", "equals", "NEW"), decision("BLOCK_TRANSACTION"), 5);
        addRule("risk_policy", "e2e_strict_v2", "dispute-extra-evidence", "DISPUTE_RULE", "DISPUTE",
                condition("unsatisfiedEvidenceCount", "greater_than", 0), decision("REQUIRE_EXTRA_EVIDENCE"), 5);
        addRule("risk_policy", "e2e_strict_v2", "review-weight-control", "REVIEW_WEIGHT_RULE", "REVIEW",
                condition("suppressionCount", "greater_than_or_equal", 0), decision("SUPPRESS_REVIEW_WEIGHT"), 5);
        addRule("risk_policy", "e2e_strict_v2", "ranking-risk-control", "RANKING_RULE", "LISTING",
                condition("riskTier", "in", java.util.List.of("HIGH", "RESTRICTED")), Map.of("decision", "ALLOW_WITH_LIMITS"), 20);
        approveAndActivate(strict);

        Map<?, ?> preview = post("/api/v1/policies/blast-radius-preview", Map.of(
                "policyName", "risk_policy", "fromPolicyVersion", "e2e_baseline_v1", "toPolicyVersion", "e2e_strict_v2",
                "requestedBy", "operator@example.com", "reason", "E2E preview"), null).getBody();
        assertThat(preview.toString()).contains("affectedListings", "dataDriven");
        Map<?, ?> blocked = evaluatePolicy("risk_policy", "e2e_strict_v2", "LISTING", listing,
                Map.of("categoryCode", "ELECTRONICS", "valueCents", 200000, "trustTier", "NEW"));
        assertThat(blocked.get("decision")).isEqualTo("REQUIRE_VERIFICATION");
        assertThat(blocked.toString()).contains("category-electronics");

        UUID exceptionId = requestAndApproveException("risk_policy", "e2e_strict_v2", "LISTING", listing, "ALLOW_HIGH_VALUE");
        assertThat(evaluatePolicy("risk_policy", "e2e_strict_v2", "LISTING", listing,
                Map.of("categoryCode", "ELECTRONICS", "valueCents", 200000)).toString()).contains(exceptionId.toString());
        assertThat(evaluatePolicy("risk_policy", "e2e_strict_v2", "LISTING", otherListing,
                Map.of("categoryCode", "ELECTRONICS", "valueCents", 200000)).toString()).doesNotContain(exceptionId.toString());
        post("/api/v1/policy-exceptions/" + exceptionId + "/revoke", Map.of(
                "actor", "risk-lead@example.com", "reason", "E2E revoke"), null);
        assertThat(evaluatePolicy("risk_policy", "e2e_strict_v2", "LISTING", listing,
                Map.of("categoryCode", "ELECTRONICS", "valueCents", 200000)).toString()).doesNotContain(exceptionId.toString());

        get("/api/v1/listings/trust-ranked-search?query=e2e&policyVersion=trust_balanced_v1");
        post("/api/v1/policy-simulations/shadow-risk", Map.of(
                "policyName", "risk_policy", "toPolicyVersion", "e2e_strict_v2",
                "requestedBy", "operator@example.com", "reason", "E2E shadow"), null);
        post("/api/v1/policy-simulations/counterfactual-ranking", Map.of(
                "policyName", "ranking_policy", "toPolicyVersion", "e2e_strict_v2",
                "requestedBy", "operator@example.com", "reason", "E2E ranking"), null);
        Flow disputed = createDisputableServiceFlow("e2e-policy-dispute");
        UUID dispute = openDispute(disputed, "e2e-policy-dispute-" + suffix());
        post("/api/v1/policy-simulations/dispute-decision", Map.of(
                "policyName", "dispute_outcome_policy", "toPolicyVersion", "e2e_strict_v2",
                "requestedBy", "operator@example.com", "reason", "E2E dispute"), null);
        assertThat(evaluatePolicy("risk_policy", "e2e_strict_v2", "DISPUTE", dispute, Map.of()).get("decision"))
                .isEqualTo("REQUIRE_EXTRA_EVIDENCE");

        post("/api/v1/policies/" + strict + "/rollback", Map.of(
                "actor", "risk-lead@example.com", "reason", "E2E restore previous policy",
                "riskAcknowledgement", "Rollback proof"), null);
        assertThat(getList("/api/v1/policies/active").getBody().toString()).contains("e2e_baseline_v1");
        assertThat(get("/actuator/health").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/actuator/health/readiness").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/api/v1/system/ping").getStatusCode().is2xxSuccessful()).isTrue();
    }

    private String capabilityStatus(UUID participantId, String capability) {
        return jdbcTemplate.queryForObject("select status from participant_capabilities where participant_id = ? and capability = ?",
                String.class, participantId, capability);
    }
}
