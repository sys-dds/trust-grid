package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnforcementLadderIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void enforcementPolicySimulationExecutionAndReversalWork() {
        createEnforcementPolicy();
        UUID participant = createCapableParticipant("enforce-" + suffix(), "Enforce", "BUY");
        assertThat(post("/api/v1/enforcement/simulate", Map.of("participantId", participant.toString(), "actionType", "SUSPEND_ACCOUNT"), null).getBody().get("requiresApproval")).isEqualTo(true);
        assertThat(post("/api/v1/enforcement/actions", Map.of(
                "participantId", participant.toString(),
                "actionType", "SUSPEND_ACCOUNT",
                "actor", "operator@example.com",
                "reason", "Execute",
                "riskAcknowledgement", "ack"
        ), null).getStatusCode().value()).isEqualTo(400);
        UUID warning = UUID.fromString(post("/api/v1/enforcement/actions", Map.of(
                "participantId", participant.toString(),
                "actionType", "WARNING",
                "actor", "operator@example.com",
                "reason", "Warn"
        ), null).getBody().get("actionId").toString());
        assertThat(warning).isNotNull();
        UUID approval = UUID.fromString(post("/api/v1/moderator-qa/severe-action-approvals", Map.of(
                "targetType", "PARTICIPANT",
                "targetId", participant.toString(),
                "actionType", "SUSPEND_ACCOUNT",
                "requestedBy", "requester@example.com",
                "reason", "Severe"
        ), null).getBody().get("approvalId").toString());
        post("/api/v1/moderator-qa/severe-action-approvals/" + approval + "/approve", Map.of("actor", "approver@example.com", "reason", "Approve", "riskAcknowledgement", "ack"), null);
        UUID action = UUID.fromString(post("/api/v1/enforcement/actions", Map.of(
                "participantId", participant.toString(),
                "actionType", "SUSPEND_ACCOUNT",
                "severeActionApprovalId", approval.toString(),
                "actor", "operator@example.com",
                "reason", "Execute",
                "riskAcknowledgement", "ack"
        ), null).getBody().get("actionId").toString());
        assertThat(countRows("select count(*) from severe_action_approvals where id = ? and consumed_by_enforcement_action_id = ?", approval, action)).isEqualTo(1);
        assertThat(post("/api/v1/enforcement/actions/" + action + "/reverse", actorRisk(), null).getBody().get("status")).isEqualTo("REVERSED");
    }
}
