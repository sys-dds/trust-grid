package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SevereActionApprovalIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void severeActionRequiresSecondPersonApproval() {
        UUID participant = createCapableParticipant("approval-" + suffix(), "Approval", "BUY");
        UUID approval = UUID.fromString(post("/api/v1/moderator-qa/severe-action-approvals", Map.of("targetType", "PARTICIPANT", "targetId", participant.toString(), "actionType", "SUSPEND_ACCOUNT", "requestedBy", "requester@example.com", "reason", "Severe"), null).getBody().get("approvalId").toString());
        assertThat(post("/api/v1/moderator-qa/severe-action-approvals/" + approval + "/approve", Map.of("actor", "requester@example.com", "reason", "same", "riskAcknowledgement", "ack"), null).getStatusCode().value()).isEqualTo(409);
        assertThat(post("/api/v1/moderator-qa/severe-action-approvals/" + approval + "/approve", Map.of("actor", "approver@example.com", "reason", "Approve", "riskAcknowledgement", "ack"), null).getBody().get("status")).isEqualTo("APPROVED");
        assertThat(post("/api/v1/enforcement/actions", Map.of(
                "participantId", participant.toString(),
                "actionType", "PERMANENT_REMOVAL_RECOMMENDED",
                "severeActionApprovalId", approval.toString(),
                "actor", "operator@example.com",
                "reason", "Mismatched action",
                "riskAcknowledgement", "ack"
        ), null).getStatusCode().value()).isEqualTo(409);
        UUID other = createCapableParticipant("approval-other-" + suffix(), "Approval Other", "BUY");
        assertThat(post("/api/v1/enforcement/actions", Map.of(
                "participantId", other.toString(),
                "actionType", "SUSPEND_ACCOUNT",
                "severeActionApprovalId", approval.toString(),
                "actor", "operator@example.com",
                "reason", "Mismatched target",
                "riskAcknowledgement", "ack"
        ), null).getStatusCode().value()).isEqualTo(409);
        assertThat(post("/api/v1/enforcement/actions", Map.of(
                "participantId", participant.toString(),
                "actionType", "SUSPEND_ACCOUNT",
                "severeActionApprovalId", approval.toString(),
                "actor", "requester@example.com",
                "reason", "Requester execute",
                "riskAcknowledgement", "ack"
        ), null).getStatusCode().value()).isEqualTo(409);
        UUID action = UUID.fromString(post("/api/v1/enforcement/actions", Map.of(
                "participantId", participant.toString(),
                "actionType", "SUSPEND_ACCOUNT",
                "severeActionApprovalId", approval.toString(),
                "actor", "operator@example.com",
                "reason", "Execute approved",
                "riskAcknowledgement", "ack"
        ), null).getBody().get("actionId").toString());
        assertThat(action).isNotNull();
        assertThat(post("/api/v1/enforcement/actions", Map.of(
                "participantId", participant.toString(),
                "actionType", "SUSPEND_ACCOUNT",
                "severeActionApprovalId", approval.toString(),
                "actor", "operator@example.com",
                "reason", "Reuse",
                "riskAcknowledgement", "ack"
        ), null).getStatusCode().value()).isEqualTo(409);
    }
}
