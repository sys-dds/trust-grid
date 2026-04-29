package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustRecoveryPlanIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void recoveryPlanMilestonesAndRestorationRecommendationWorkWithoutAutoRestore() {
        UUID participant = createCapableParticipant("recovery-" + suffix(), "Recovery", "BUY");
        UUID plan = createRecoveryPlan(participant);
        post("/api/v1/trust-recovery/plans/" + plan + "/milestones", Map.of("milestoneKey", "complete_verification", "status", "COMPLETED", "evaluatedBy", "operator@example.com", "reason", "Evaluate"), null);
        Map<?, ?> rec = post("/api/v1/trust-recovery/plans/" + plan + "/recommend-capability-restoration", Map.of("actor", "operator@example.com", "reason", "Recommend"), null).getBody();
        assertThat(rec.get("automaticRestore")).isEqualTo(false);
        assertThat(rec.get("recommendation")).isEqualTo("ELIGIBLE_FOR_REVIEW");

        UUID suspended = createCapableParticipant("recovery-suspended-" + suffix(), "Recovery Suspended", "BUY");
        updateStatus(suspended, "SUSPENDED");
        UUID suspendedPlan = createRecoveryPlan(suspended);
        Map<?, ?> suspendedRec = post("/api/v1/trust-recovery/plans/" + suspendedPlan + "/recommend-capability-restoration", Map.of("actor", "operator@example.com", "reason", "Recommend"), null).getBody();
        assertThat(suspendedRec.get("recommendation")).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(suspendedRec.get("noAutomaticRestore")).isEqualTo(true);

        UUID closed = createCapableParticipant("recovery-closed-" + suffix(), "Recovery Closed", "BUY");
        updateStatus(closed, "CLOSED");
        UUID closedPlan = createRecoveryPlan(closed);
        assertThat(post("/api/v1/trust-recovery/plans/" + closedPlan + "/recommend-capability-restoration", Map.of("actor", "operator@example.com", "reason", "Recommend"), null).getBody().get("recommendation"))
                .isEqualTo("MANUAL_REVIEW_REQUIRED");

        UUID severe = createCapableParticipant("recovery-severe-" + suffix(), "Recovery Severe", "BUY");
        UUID approval = UUID.fromString(post("/api/v1/moderator-qa/severe-action-approvals", Map.of("targetType", "PARTICIPANT", "targetId", severe.toString(), "actionType", "SUSPEND_ACCOUNT", "requestedBy", "requester@example.com", "reason", "Severe"), null).getBody().get("approvalId").toString());
        post("/api/v1/moderator-qa/severe-action-approvals/" + approval + "/approve", Map.of("actor", "approver@example.com", "reason", "Approve", "riskAcknowledgement", "ack"), null);
        post("/api/v1/enforcement/actions", Map.of("participantId", severe.toString(), "actionType", "SUSPEND_ACCOUNT", "severeActionApprovalId", approval.toString(), "actor", "operator@example.com", "reason", "Execute", "riskAcknowledgement", "ack"), null);
        UUID severePlan = createRecoveryPlan(severe);
        assertThat(post("/api/v1/trust-recovery/plans/" + severePlan + "/recommend-capability-restoration", Map.of("actor", "operator@example.com", "reason", "Recommend"), null).getBody().get("recommendation"))
                .isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(countRows("select count(*) from marketplace_ops_queue_items where queue_type = 'CAPABILITY_RESTORATION_REVIEW' and signals_json->>'noAutomaticRestore' = 'true'")).isGreaterThanOrEqualTo(4);
    }
}
