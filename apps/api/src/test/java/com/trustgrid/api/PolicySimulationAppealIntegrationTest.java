package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PolicySimulationAppealIntegrationTest extends Tg101To160IntegrationTestSupport {

    @Test
    void policySimulationRetentionAndAppealsWorkWithoutLiveStateMutation() {
        UUID participant = createCapableParticipant("policy-appeal-" + suffix(), "Policy Appeal", "BUY");
        Map<?, ?> policy = post("/api/v1/policies", Map.of(
                "policyName", "risk_policy",
                "policyVersion", "strict_v1",
                "policy", Map.of("threshold", 75),
                "createdBy", "operator@example.com",
                "reason", "Simulation proof"), null).getBody();
        post("/api/v1/policies/" + policy.get("policyId") + "/activate", Map.of(), null);
        assertThat(getList("/api/v1/policies/active").getBody().toString()).contains("strict_v1");
        post("/api/v1/policy-simulations/trust", Map.of("toPolicyVersion", "strict_v1", "requestedBy", "operator@example.com", "reason", "Trust sim"), null);
        post("/api/v1/policy-simulations/shadow-risk", Map.of("toPolicyVersion", "strict_v1", "requestedBy", "operator@example.com", "reason", "Shadow"), null);
        post("/api/v1/policy-simulations/counterfactual-ranking", Map.of("toPolicyVersion", "strict_v1", "requestedBy", "operator@example.com", "reason", "Ranking"), null);
        post("/api/v1/policy-simulations/dispute-decision", Map.of("toPolicyVersion", "strict_v1", "requestedBy", "operator@example.com", "reason", "Dispute"), null);
        Map<?, ?> appeal = post("/api/v1/participants/" + participant + "/appeals", Map.of(
                "targetType", "PARTICIPANT", "targetId", participant.toString(),
                "appealReason", "Please review", "metadata", Map.of("source", "test")), null).getBody();
        post("/api/v1/appeals/" + appeal.get("appealId") + "/decide", Map.of(
                "decision", "CAPABILITY_RESTORED", "decidedBy", "moderator@example.com", "reason", "Appeal accepted"), null);
        assertThat(get("/api/v1/data-retention/summary").getBody().get("deletionJob")).isEqualTo(false);
        assertThat(countRows("select count(*) from policy_simulation_runs")).isGreaterThanOrEqualTo(4);
        assertThat(countRows("select count(*) from marketplace_events where event_type = 'APPEAL_DECIDED'")).isPositive();
    }
}
