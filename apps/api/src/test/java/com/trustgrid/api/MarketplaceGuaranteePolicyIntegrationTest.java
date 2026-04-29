package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarketplaceGuaranteePolicyIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void guaranteePolicyEligibilityAndTimelineWork() {
        createGuaranteePolicy();
        Flow flow = createCompletedServiceFlow("guarantee-" + suffix());
        UUID decision = UUID.fromString(post("/api/v1/marketplace-guarantees/eligibility-simulate", Map.of(
                "transactionId", flow.transactionId().toString(), "participantId", flow.buyerId().toString(), "policyName", "guarantee_policy", "policyVersion", "guarantee_policy_v1"
        ), null).getBody().get("decisionId").toString());
        assertThat(get("/api/v1/marketplace-guarantees/decisions/" + decision).getBody().get("decision")).isEqualTo("ELIGIBLE");
        assertThat(getList("/api/v1/marketplace-guarantees/decisions/" + decision + "/timeline").getBody()).isNotEmpty();
    }
}
