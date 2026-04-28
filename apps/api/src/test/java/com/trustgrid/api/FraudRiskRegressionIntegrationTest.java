package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FraudRiskRegressionIntegrationTest extends EvidenceDisputeRiskIntegrationTestSupport {

    @Test
    void fraudRiskRegressionScenariosProduceExplainableNonGraphSignals() {
        UUID seller = createCapableParticipant("fraud-seller-" + suffix(), "New Seller", "SELL_ITEMS");
        UUID item = createListing(seller, "ITEM_LISTING", "ELECTRONICS", "High value device " + suffix(), 75000L, null, itemDetails(true));
        Map<?, ?> publish = publish(item);
        assertThat(publish.get("status")).isIn("UNDER_REVIEW", "DRAFT");
        assertThat(countRows("select count(*) from listing_evidence_requirements where listing_id = ?", item)).isGreaterThanOrEqualTo(2);

        Flow flow = createCompletedServiceFlow("fraud-risk");
        post("/api/v1/transactions/" + flow.transactionId() + "/off-platform-contact-reports", Map.of(
                "reporterParticipantId", flow.buyerId().toString(),
                "reportedParticipantId", flow.providerId().toString(),
                "reportText", "Asked to coordinate away from TrustGrid.",
                "reason", "Off-platform attempt"
        ), "fraud-risk-report-" + suffix());
        post("/api/v1/participants/" + flow.buyerId() + "/synthetic-risk-signals", Map.of(
                "signalType", "ACCOUNT_CLUSTER_SIMULATED",
                "signalHash", "sha256-risk-cluster-" + suffix(),
                "riskWeight", 30,
                "source", "SIMULATED_TEST_SIGNAL",
                "reason", "Synthetic risk cluster"
        ), "fraud-risk-signal-" + suffix());

        Map<?, ?> explanation = get("/api/v1/risk/explain?targetType=TRANSACTION&targetId=" + flow.transactionId()).getBody();
        assertThat(explanation.get("matchedRules").toString()).contains("off_platform");
        assertThat(explanation.get("nextSteps").toString()).isNotBlank();
        assertThat(countRows("select count(*) from risk_decisions where decision = 'REQUIRE_MANUAL_REVIEW'")).isPositive();
    }
}
