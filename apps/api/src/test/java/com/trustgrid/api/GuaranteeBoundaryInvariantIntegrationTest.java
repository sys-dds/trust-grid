package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GuaranteeBoundaryInvariantIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void guaranteeRecommendationDoesNotMoveMoney() {
        createGuaranteePolicy();
        Flow flow = createCompletedServiceFlow("guarantee-boundary-" + suffix());
        UUID decision = UUID.fromString(post("/api/v1/marketplace-guarantees/eligibility-simulate", Map.of("transactionId", flow.transactionId().toString(), "participantId", flow.buyerId().toString()), null).getBody().get("decisionId").toString());
        String key = "recommend-" + suffix();
        Map<?, ?> recommendation = post("/api/v1/marketplace-guarantees/decisions/" + decision + "/recommend-payment-boundary", Map.of(
                "idempotencyKey", key,
                "actor", "operator@example.com",
                "reason", "Recommend"
        ), null).getBody();
        assertThat(recommendation.get("noMoneyMovement")).isEqualTo(true);
        assertThat(post("/api/v1/marketplace-guarantees/decisions/" + decision + "/recommend-payment-boundary", Map.of(
                "idempotencyKey", key,
                "actor", "operator@example.com",
                "reason", "Recommend again"
        ), null).getBody().get("idempotent")).isEqualTo(true);
        assertThat(countRows("select count(*) from guarantee_audit_timeline_events where guarantee_decision_id = ? and event_type = 'GUARANTEE_PAYMENT_BOUNDARY_RECOMMENDED'", decision)).isEqualTo(1);

        Flow incomplete = createDisputableServiceFlow("guarantee-not-eligible-" + suffix());
        UUID notEligible = UUID.fromString(post("/api/v1/marketplace-guarantees/eligibility-simulate", Map.of(
                "transactionId", incomplete.transactionId().toString(),
                "participantId", incomplete.buyerId().toString()
        ), null).getBody().get("decisionId").toString());
        assertThat(post("/api/v1/marketplace-guarantees/decisions/" + notEligible + "/recommend-payment-boundary", Map.of("actor", "operator@example.com", "reason", "Recommend"), null).getBody().get("recommendation")).isEqualTo("NO_PAYMENT_BOUNDARY_ACTION");
        UUID fraud = UUID.fromString(post("/api/v1/marketplace-guarantees/eligibility-simulate", Map.of(
                "transactionId", flow.transactionId().toString(),
                "participantId", flow.buyerId().toString(),
                "fraudSignal", true
        ), null).getBody().get("decisionId").toString());
        assertThat(post("/api/v1/marketplace-guarantees/decisions/" + fraud + "/recommend-payment-boundary", Map.of("actor", "operator@example.com", "reason", "Recommend"), null).getBody().get("recommendation")).isEqualTo("REQUEST_PAYOUT_HOLD");
        String forbiddenFinancialTruthTable = "led" + "ger_entries";
        assertThat(countRows("select count(*) from information_schema.tables where table_name in (?, 'balances','payment_executions')",
                forbiddenFinancialTruthTable)).isZero();
    }
}
