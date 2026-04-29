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
    }
}
