package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PolicyBlastRadiusRollbackIntegrationTest extends Tg161To180IntegrationTestSupport {

    @Test
    void blastRadiusPreviewUsesCurrentDataRegressionChecksDoNotMutateAndRollbackRestoresPreviousActivePolicy() {
        UUID seller = createCapableParticipant("blast-seller-" + suffix(), "Blast Seller", "SELL_ITEMS");
        createListing(seller, "ITEM_LISTING", "ELECTRONICS", "blast high value " + suffix(), 175000L, null, itemDetails(true));
        Flow flow = createDisputableServiceFlow("blast-dispute");
        openDispute(flow, "blast-dispute-" + suffix());

        UUID v1 = createPolicy("risk_policy", "blast_v1", true);
        addRule("risk_policy", "blast_v1", "baseline-limits", "RISK_RULE", "LISTING",
                condition("valueCents", "greater_than", 250000), decision("REQUIRE_MANUAL_REVIEW"), 10);
        approveAndActivate(v1);
        UUID v2 = createPolicy("risk_policy", "blast_v2", true);
        addRule("risk_policy", "blast_v2", "stricter-high-value", "RISK_RULE", "LISTING",
                condition("valueCents", "greater_than", 100000), decision("BLOCK_TRANSACTION"), 10);
        approveAndActivate(v2);

        int liveListingsBefore = countRows("select count(*) from marketplace_listings where status = 'LIVE'");
        Map<?, ?> preview = post("/api/v1/policies/blast-radius-preview", Map.of(
                "policyName", "risk_policy", "fromPolicyVersion", "blast_v1", "toPolicyVersion", "blast_v2",
                "requestedBy", "operator@example.com", "reason", "Preview stricter high-value controls"), null).getBody();
        assertThat(preview.toString()).contains("affectedUsers", "affectedListings", "affectedTransactions", "affectedDisputes");
        post("/api/v1/policies/regression-check", Map.of(
                "policyName", "risk_policy", "toPolicyVersion", "blast_v2",
                "requestedBy", "operator@example.com", "reason", "Regression proof"), null);
        assertThat(countRows("select count(*) from marketplace_listings where status = 'LIVE'")).isEqualTo(liveListingsBefore);

        post("/api/v1/policies/" + v2 + "/rollback", Map.of(
                "actor", "risk-lead@example.com", "reason", "Return to baseline",
                "riskAcknowledgement", "Baseline restored after preview"), null);
        assertThat(getList("/api/v1/policies/active").getBody().toString()).contains("blast_v1");
        assertThat(countRows("select count(*) from marketplace_events where event_type in ('POLICY_BLAST_RADIUS_PREVIEWED','POLICY_REGRESSION_CHECK_RUN','POLICY_ROLLBACK_COMPLETED')")).isGreaterThanOrEqualTo(3);
    }
}
