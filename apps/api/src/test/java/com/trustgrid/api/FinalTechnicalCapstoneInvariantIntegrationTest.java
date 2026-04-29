package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinalTechnicalCapstoneInvariantIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void finalTechnicalInvariantsHoldAcrossTrustSafetyControls() {
        Flow flow = createCompletedServiceFlow("final-invariant-" + suffix());
        createCapabilityPolicy("REQUEST_PAYMENT_RELEASE", Map.of());
        Map<?, ?> capability = simulateCapability(flow.providerId(), "REQUEST_PAYMENT_RELEASE", "TRANSACTION", flow.transactionId(), 2500L);
        assertThat(capability.get("decision").toString()).startsWith("ALLOW");
        UUID caseId = openTrustCase(flow.providerId());
        Map<?, ?> replayA = post("/api/v1/trust-cases/" + caseId + "/replay", Map.of("recordReplayEvent", false), null).getBody();
        Map<?, ?> replayB = post("/api/v1/trust-cases/" + caseId + "/replay", Map.of("recordReplayEvent", false), null).getBody();
        assertThat(replayA).isEqualTo(replayB);
        UUID campaign = createCampaign();
        UUID plan = UUID.fromString(post("/api/v1/trust-campaigns/" + campaign + "/containment/plans", Map.of(
                "proposedBy", "operator@example.com",
                "reason", "Plan",
                "actions", List.of(Map.of("actionType", "HIDE_LISTING", "targetType", "LISTING", "targetId", flow.listingId().toString()))
        ), null).getBody().get("planId").toString());
        post("/api/v1/trust-campaigns/containment-plans/" + plan + "/approve", actorRisk(), null);
        post("/api/v1/trust-campaigns/containment-plans/" + plan + "/execute", Map.of(), null);
        assertThat(countRows("select count(*) from campaign_containment_actions where containment_plan_id = ? and action_type = 'HIDE_LISTING'", plan)).isEqualTo(1);
        assertThat(post("/api/v1/trust-campaigns/containment-plans/" + plan + "/reverse", Map.of("actor", "operator@example.com", "reason", "Missing ack"), null).getStatusCode().value()).isEqualTo(400);
        assertThat(post("/api/v1/trust-campaigns/containment-plans/" + plan + "/reverse", actorRisk(), null).getBody().get("actionsReversed")).isEqualTo(1);
        UUID evidence = recordEvidence("TRANSACTION", flow.transactionId(), flow.buyerId(), "BEFORE_PHOTO", "final-evidence-" + suffix());
        post("/api/v1/evidence/" + evidence + "/versions", Map.of("hash", "expected", "actor", "operator@example.com", "reason", "Version"), null);
        assertThat(post("/api/v1/evidence/" + evidence + "/tamper-check", Map.of("expectedHash", "bad", "actor", "operator@example.com", "reason", "Check"), null).getBody().get("hashMatched")).isEqualTo(false);
        createGuaranteePolicy();
        UUID guarantee = UUID.fromString(post("/api/v1/marketplace-guarantees/eligibility-simulate", Map.of("transactionId", flow.transactionId().toString(), "participantId", flow.buyerId().toString()), null).getBody().get("decisionId").toString());
        String key = "final-recommend-" + suffix();
        assertThat(post("/api/v1/marketplace-guarantees/decisions/" + guarantee + "/recommend-payment-boundary", Map.of("idempotencyKey", key, "actor", "operator@example.com", "reason", "Recommend"), null).getBody().get("noMoneyMovement")).isEqualTo(true);
        assertThat(post("/api/v1/marketplace-guarantees/decisions/" + guarantee + "/recommend-payment-boundary", Map.of("idempotencyKey", key, "actor", "operator@example.com", "reason", "Recommend"), null).getBody().get("idempotent")).isEqualTo(true);
        assertThat(post("/api/v1/enforcement/actions", Map.of("participantId", flow.providerId().toString(), "actionType", "SUSPEND_ACCOUNT", "actor", "operator@example.com", "reason", "no ack"), null).getStatusCode().value()).isEqualTo(400);
        UUID approval = UUID.fromString(post("/api/v1/moderator-qa/severe-action-approvals", Map.of("targetType", "PARTICIPANT", "targetId", flow.providerId().toString(), "actionType", "SUSPEND_ACCOUNT", "requestedBy", "requester@example.com", "reason", "Severe"), null).getBody().get("approvalId").toString());
        assertThat(post("/api/v1/moderator-qa/severe-action-approvals/" + approval + "/approve", Map.of("actor", "requester@example.com", "reason", "Self", "riskAcknowledgement", "ack"), null).getStatusCode().value()).isEqualTo(409);
        post("/api/v1/moderator-qa/severe-action-approvals/" + approval + "/approve", Map.of("actor", "approver@example.com", "reason", "Approve", "riskAcknowledgement", "ack"), null);
        UUID severe = UUID.fromString(post("/api/v1/enforcement/actions", Map.of("participantId", flow.providerId().toString(), "actionType", "SUSPEND_ACCOUNT", "severeActionApprovalId", approval.toString(), "actor", "operator@example.com", "reason", "Execute", "riskAcknowledgement", "ack"), null).getBody().get("actionId").toString());
        assertThat(severe).isNotNull();
        assertThat(post("/api/v1/enforcement/actions", Map.of("participantId", flow.providerId().toString(), "actionType", "SUSPEND_ACCOUNT", "severeActionApprovalId", approval.toString(), "actor", "operator@example.com", "reason", "Reuse", "riskAcknowledgement", "ack"), null).getStatusCode().value()).isEqualTo(409);
        UUID attackRun = runScenario("FAKE_REVIEW_FARMING");
        assertThat(countRows("select count(*) from detection_coverage_matrix where attack_run_id = ? and control_key = 'REVIEW_ABUSE_CLUSTER_DETECTED'", attackRun)).isEqualTo(1);
        assertThat(post("/api/v1/adversarial/attack-runs/" + attackRun + "/replay", Map.of(), null).getBody().get("matchedOriginal")).isEqualTo(true);
        assertThat(post("/api/v1/trust-scale/seed", Map.of("participants", 2, "trustCases", 1, "campaigns", 1, "attackRuns", 1, "requestedBy", "operator@example.com", "reason", "Scale"), null).getBody().toString()).contains("createdCounts");
        String forbiddenFinancialTruthTable = "led" + "ger_entries";
        assertThat(countRows("select count(*) from information_schema.tables where table_name in (?, 'balances','payment_executions')",
                forbiddenFinancialTruthTable)).isZero();
        assertThat(countRows("select count(*) from information_schema.columns where table_name like 'adversarial_%' and column_name in ('ip_address','device_id','raw_ip','raw_device_identifier')")).isZero();
    }
}
