package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ReputationSnapshotAndTierTransitionIntegrationTest extends EvidenceDisputeRiskIntegrationTestSupport {

    @Test
    void reputationSnapshotUsesSignalsAndTierTransitionsRespectForcedStatus() {
        Flow flow = createCompletedServiceFlow("reputation");
        post("/api/v1/transactions/" + flow.transactionId() + "/reviews", Map.of(
                "reviewerParticipantId", flow.buyerId().toString(),
                "reviewedParticipantId", flow.providerId().toString(),
                "overallRating", 5,
                "reason", "Positive review"
        ), "reputation-review-" + suffix());

        Map<?, ?> reputation = get("/api/v1/participants/" + flow.providerId() + "/reputation").getBody();
        assertThat((Integer) reputation.get("trustScore")).isGreaterThan(500);
        assertThat(reputation.get("trustTier")).isIn("LIMITED", "STANDARD", "TRUSTED", "HIGH_TRUST");

        post("/api/v1/participants/" + flow.providerId() + "/account-status", Map.of(
                "newStatus", "SUSPENDED",
                "actor", "operator@example.com",
                "reason", "Trust status test"
        ), "reputation-suspend-" + suffix());
        assertThat(get("/api/v1/participants/" + flow.providerId() + "/reputation").getBody().get("trustTier")).isEqualTo("SUSPENDED");
    }
}
