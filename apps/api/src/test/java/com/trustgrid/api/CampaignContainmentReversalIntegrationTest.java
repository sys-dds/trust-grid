package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CampaignContainmentReversalIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void approvedExecutionAndScopedRiskAcknowledgedReversalWork() {
        UUID campaign = createCampaign();
        Flow flow = createCompletedServiceFlow("containment-reverse-" + suffix());
        Flow unrelated = createCompletedServiceFlow("containment-unrelated-" + suffix());
        UUID plan = UUID.fromString(post("/api/v1/trust-campaigns/" + campaign + "/containment/plans", Map.of(
                "proposedBy", "operator@example.com",
                "reason", "Plan",
                "actions", List.of(Map.of("actionType", "HIDE_LISTING", "targetType", "LISTING", "targetId", flow.listingId().toString()))
        ), null).getBody().get("planId").toString());
        post("/api/v1/trust-campaigns/containment-plans/" + plan + "/approve", actorRisk(), null);
        assertThat(post("/api/v1/trust-campaigns/containment-plans/" + plan + "/execute", Map.of(), null).getBody().get("status")).isEqualTo("EXECUTED");
        assertThat(jdbcTemplate.queryForObject("select status from marketplace_listings where id = ?", String.class, flow.listingId())).isEqualTo("HIDDEN");
        assertThat(post("/api/v1/trust-campaigns/containment-plans/" + plan + "/reverse", Map.of(
                "actor", "operator@example.com",
                "reason", "Missing ack"
        ), null).getStatusCode().value()).isEqualTo(400);
        Map<?, ?> reversed = post("/api/v1/trust-campaigns/containment-plans/" + plan + "/reverse", actorRisk(), null).getBody();
        assertThat(reversed.get("status")).isEqualTo("REVERSED");
        assertThat(jdbcTemplate.queryForObject("select status from marketplace_listings where id = ?", String.class, flow.listingId())).isEqualTo("LIVE");
        assertThat(jdbcTemplate.queryForObject("select status from marketplace_listings where id = ?", String.class, unrelated.listingId())).isEqualTo("LIVE");
        assertThat(post("/api/v1/trust-campaigns/containment-plans/" + plan + "/reverse", actorRisk(), null).getBody().get("idempotent")).isEqualTo(true);
        assertThat(countRows("select count(*) from campaign_containment_actions where containment_plan_id = ? and status = 'REVERSED' and reversal_json->>'riskAcknowledgement' is not null", plan)).isEqualTo(1);

        UUID conflictPlan = UUID.fromString(post("/api/v1/trust-campaigns/" + campaign + "/containment/plans", Map.of(
                "proposedBy", "operator@example.com",
                "reason", "Conflict plan",
                "actions", List.of(Map.of("actionType", "HIDE_LISTING", "targetType", "LISTING", "targetId", unrelated.listingId().toString()))
        ), null).getBody().get("planId").toString());
        post("/api/v1/trust-campaigns/containment-plans/" + conflictPlan + "/approve", actorRisk(), null);
        post("/api/v1/trust-campaigns/containment-plans/" + conflictPlan + "/execute", Map.of(), null);
        jdbcTemplate.update("update marketplace_listings set status = 'REJECTED' where id = ?", unrelated.listingId());
        assertThat(post("/api/v1/trust-campaigns/containment-plans/" + conflictPlan + "/reverse", actorRisk(), null).getBody().get("conflicts")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select status from marketplace_listings where id = ?", String.class, unrelated.listingId())).isEqualTo("REJECTED");
    }
}
