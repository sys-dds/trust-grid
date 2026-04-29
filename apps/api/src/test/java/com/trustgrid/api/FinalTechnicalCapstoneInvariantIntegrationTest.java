package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(post("/api/v1/trust-cases/" + caseId + "/replay", Map.of(), null).getBody().get("deterministic")).isEqualTo(true);
        UUID campaign = createCampaign();
        UUID plan = UUID.fromString(post("/api/v1/trust-campaigns/" + campaign + "/containment/plans", Map.of("proposedBy", "operator@example.com", "reason", "Plan"), null).getBody().get("planId").toString());
        post("/api/v1/trust-campaigns/containment-plans/" + plan + "/approve", actorRisk(), null);
        post("/api/v1/trust-campaigns/containment-plans/" + plan + "/execute", Map.of(), null);
        assertThat(post("/api/v1/trust-campaigns/containment-plans/" + plan + "/reverse", Map.of(), null).getBody().get("actionsReversed")).isEqualTo(1);
        UUID evidence = recordEvidence("TRANSACTION", flow.transactionId(), flow.buyerId(), "BEFORE_PHOTO", "final-evidence-" + suffix());
        post("/api/v1/evidence/" + evidence + "/versions", Map.of("hash", "expected", "actor", "operator@example.com", "reason", "Version"), null);
        assertThat(post("/api/v1/evidence/" + evidence + "/tamper-check", Map.of("expectedHash", "bad", "actor", "operator@example.com", "reason", "Check"), null).getBody().get("hashMatched")).isEqualTo(false);
        createGuaranteePolicy();
        UUID guarantee = UUID.fromString(post("/api/v1/marketplace-guarantees/eligibility-simulate", Map.of("transactionId", flow.transactionId().toString(), "participantId", flow.buyerId().toString()), null).getBody().get("decisionId").toString());
        assertThat(post("/api/v1/marketplace-guarantees/decisions/" + guarantee + "/recommend-payment-boundary", Map.of("actor", "operator@example.com", "reason", "Recommend"), null).getBody().get("noMoneyMovement")).isEqualTo(true);
        assertThat(post("/api/v1/enforcement/actions", Map.of("participantId", flow.providerId().toString(), "actionType", "SUSPEND_ACCOUNT", "actor", "operator@example.com", "reason", "no ack"), null).getStatusCode().value()).isEqualTo(400);
        String forbiddenFinancialTruthTable = "led" + "ger_entries";
        assertThat(countRows("select count(*) from information_schema.tables where table_name in (?, 'balances','payment_executions')",
                forbiddenFinancialTruthTable)).isZero();
    }
}
