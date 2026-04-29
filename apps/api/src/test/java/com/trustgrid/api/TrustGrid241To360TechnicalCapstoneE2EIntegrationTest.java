package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustGrid241To360TechnicalCapstoneE2EIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void trustSafetyTechnicalCapstoneFlowWorksEndToEnd() {
        Flow flow = createCompletedServiceFlow("capstone-" + suffix());
        UUID caseId = openTrustCase(flow.providerId());
        post("/api/v1/trust-cases/" + caseId + "/assign", Map.of("assignedTo", "analyst@example.com", "actor", "operator@example.com", "reason", "Assign"), null);
        post("/api/v1/trust-cases/" + caseId + "/apply-playbook", Map.of("actor", "operator@example.com", "reason", "Playbook"), null);
        UUID campaign = createCampaign();
        post("/api/v1/trust-campaigns/" + campaign + "/graph/rebuild", Map.of(), null);
        UUID plan = UUID.fromString(post("/api/v1/trust-campaigns/" + campaign + "/containment/plans", Map.of("proposedBy", "operator@example.com", "reason", "Plan"), null).getBody().get("planId").toString());
        post("/api/v1/trust-campaigns/containment-plans/" + plan + "/approve", actorRisk(), null);
        post("/api/v1/trust-campaigns/containment-plans/" + plan + "/execute", Map.of(), null);
        post("/api/v1/trust-campaigns/containment-plans/" + plan + "/reverse", Map.of(), null);
        UUID evidence = recordEvidence("TRANSACTION", flow.transactionId(), flow.buyerId(), "BEFORE_PHOTO", "capstone-evidence-" + suffix());
        post("/api/v1/evidence/" + evidence + "/versions", Map.of("hash", "good", "actor", "operator@example.com", "reason", "Version"), null);
        post("/api/v1/evidence/" + evidence + "/legal-hold", Map.of("actor", "operator@example.com", "reason", "Hold"), null);
        createGuaranteePolicy();
        UUID guarantee = UUID.fromString(post("/api/v1/marketplace-guarantees/eligibility-simulate", Map.of("transactionId", flow.transactionId().toString(), "participantId", flow.buyerId().toString()), null).getBody().get("decisionId").toString());
        assertThat(post("/api/v1/marketplace-guarantees/decisions/" + guarantee + "/recommend-payment-boundary", Map.of("actor", "operator@example.com", "reason", "Recommend"), null).getBody().get("noMoneyMovement")).isEqualTo(true);
        createEnforcementPolicy();
        UUID enforcement = UUID.fromString(post("/api/v1/enforcement/actions", Map.of("participantId", flow.providerId().toString(), "actionType", "WARNING", "actor", "operator@example.com", "reason", "Warn"), null).getBody().get("actionId").toString());
        UUID recovery = createRecoveryPlan(flow.providerId());
        post("/api/v1/trust-recovery/plans/" + recovery + "/milestones", Map.of("milestoneKey", "complete_verification", "status", "IN_PROGRESS", "evaluatedBy", "operator@example.com", "reason", "Eval"), null);
        post("/api/v1/moderator-qa/reviews", Map.of("enforcementActionId", enforcement.toString(), "reviewer", "lead@example.com", "qaStatus", "PASS", "reason", "QA"), null);
        UUID run = runScenario("FAKE_REVIEW_FARMING");
        assertThat(getList("/api/v1/adversarial/attack-runs/" + run + "/coverage").getBody()).isNotEmpty();
        assertThat(get("/api/v1/trust-dossiers/transactions/" + flow.transactionId()).getBody().get("dossierType")).isEqualTo("TRANSACTION");
        post("/api/v1/consistency/checks/full", actorRisk(), null);
        post("/api/v1/data-repair/recommendations/generate", Map.of(), null);
        assertThat(post("/api/v1/trust-scale/seed", Map.of("requestedBy", "operator@example.com", "reason", "Scale"), null).getBody().get("status")).isEqualTo("SUCCEEDED");
        assertThat(get("/api/v1/system/ping").getBody().get("status")).isEqualTo("OK");
    }
}
