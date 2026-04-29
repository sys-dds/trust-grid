package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CampaignGraphContainmentIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void campaignGraphBlastRadiusSimulationAndRequestedActionsExecute() {
        UUID campaign = createCampaign();
        Flow flow = createCompletedServiceFlow("containment-actions-" + suffix());
        assertThat(post("/api/v1/trust-campaigns/" + campaign + "/graph/rebuild", Map.of(), null).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(getList("/api/v1/trust-campaigns/" + campaign + "/graph").getBody()).isNotEmpty();
        assertThat(get("/api/v1/trust-campaigns/" + campaign + "/blast-radius").getBody().get("deterministic")).isEqualTo(true);
        assertThat(post("/api/v1/trust-campaigns/" + campaign + "/containment/simulate", Map.of("actor", "operator@example.com", "reason", "Simulate"), null).getBody().get("wouldMutate")).isEqualTo(false);

        UUID plan = UUID.fromString(post("/api/v1/trust-campaigns/" + campaign + "/containment/plans", Map.of(
                "proposedBy", "operator@example.com",
                "reason", "Plan requested actions",
                "actions", List.of(
                        Map.of("actionType", "HIDE_LISTING", "targetType", "LISTING", "targetId", flow.listingId().toString()),
                        Map.of("actionType", "CREATE_OPS_QUEUE_ITEM", "targetType", "PARTICIPANT", "targetId", flow.providerId().toString()),
                        Map.of("actionType", "REQUEST_PAYOUT_HOLD", "targetType", "TRANSACTION", "targetId", flow.transactionId().toString())
                )
        ), null).getBody().get("planId").toString());
        post("/api/v1/trust-campaigns/containment-plans/" + plan + "/approve", actorRisk(), null);
        Map<?, ?> executed = post("/api/v1/trust-campaigns/containment-plans/" + plan + "/execute", Map.of(), null).getBody();
        assertThat(executed.get("actionsExecuted")).isEqualTo(3);
        assertThat(countRows("select count(*) from campaign_containment_actions where containment_plan_id = ? and action_type in ('HIDE_LISTING','CREATE_OPS_QUEUE_ITEM','REQUEST_PAYOUT_HOLD')", plan)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("select status from marketplace_listings where id = ?", String.class, flow.listingId())).isEqualTo("HIDDEN");
        assertThat(countRows("select count(*) from marketplace_ops_queue_items where target_id = ? and queue_type = 'CREATE_OPS_QUEUE_ITEM'", flow.providerId())).isEqualTo(1);
        assertThat(countRows("select count(*) from payment_boundary_events where transaction_id = ? and event_type = 'MARKETPLACE_PAYOUT_HOLD_REQUESTED'", flow.transactionId())).isEqualTo(1);
        assertThat(post("/api/v1/trust-campaigns/containment-plans/" + plan + "/execute", Map.of(), null).getBody().get("idempotent")).isEqualTo(true);

        UUID badPlan = UUID.fromString(post("/api/v1/trust-campaigns/" + campaign + "/containment/plans", Map.of(
                "proposedBy", "operator@example.com",
                "reason", "Bad plan",
                "actions", List.of(Map.of("actionType", "UNSUPPORTED", "targetType", "LISTING", "targetId", flow.listingId().toString()))
        ), null).getBody().get("planId").toString());
        post("/api/v1/trust-campaigns/containment-plans/" + badPlan + "/approve", actorRisk(), null);
        assertThat(post("/api/v1/trust-campaigns/containment-plans/" + badPlan + "/execute", Map.of(), null).getStatusCode().value()).isEqualTo(400);
        assertThat(countRows("select count(*) from campaign_containment_actions where containment_plan_id = ?", badPlan)).isZero();
    }
}
