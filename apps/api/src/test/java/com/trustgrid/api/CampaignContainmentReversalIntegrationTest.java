package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CampaignContainmentReversalIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void approvedExecutionAndScopedReversalWork() {
        UUID campaign = createCampaign();
        UUID plan = UUID.fromString(post("/api/v1/trust-campaigns/" + campaign + "/containment/plans", Map.of("proposedBy", "operator@example.com", "reason", "Plan"), null).getBody().get("planId").toString());
        post("/api/v1/trust-campaigns/containment-plans/" + plan + "/approve", actorRisk(), null);
        assertThat(post("/api/v1/trust-campaigns/containment-plans/" + plan + "/execute", Map.of(), null).getBody().get("status")).isEqualTo("EXECUTED");
        assertThat(post("/api/v1/trust-campaigns/containment-plans/" + plan + "/reverse", Map.of(), null).getBody().get("status")).isEqualTo("REVERSED");
    }
}
