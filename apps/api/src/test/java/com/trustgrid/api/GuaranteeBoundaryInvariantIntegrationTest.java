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
        Map<?, ?> recommendation = post("/api/v1/marketplace-guarantees/decisions/" + decision + "/recommend-payment-boundary", Map.of("actor", "operator@example.com", "reason", "Recommend"), null).getBody();
        assertThat(recommendation.get("noMoneyMovement")).isEqualTo(true);
        String forbiddenFinancialTruthTable = "led" + "ger_entries";
        assertThat(countRows("select count(*) from information_schema.tables where table_name in (?, 'balances','payment_executions')",
                forbiddenFinancialTruthTable)).isZero();
    }
}
