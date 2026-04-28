package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionInvariantCarryForwardIntegrationTest extends EvidenceDisputeRiskIntegrationTestSupport {

    @Test
    void expandedInvariantVerifierPassesGoodStateAndFailsSeededBadStateWithoutRepair() {
        Flow good = createCompletedServiceFlow("invariant-good");
        Map<?, ?> goodVerify = post("/api/v1/transactions/invariants/verify", Map.of(
                "transactionId", good.transactionId().toString()
        ), null).getBody();
        assertThat(list(goodVerify, "results")).allSatisfy(result ->
                assertThat(((Map<?, ?>) result).get("status")).isEqualTo("PASS"));

        UUID bad = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into marketplace_transactions (
                    id, listing_id, transaction_type, requester_participant_id, provider_participant_id,
                    status, value_amount_cents, currency, risk_status
                ) values (?, ?, 'SERVICE_BOOKING', ?, ?, 'SHIPPED', 2500, 'GBP', 'ALLOWED')
                """, bad, good.listingId(), good.buyerId(), good.providerId());
        Map<?, ?> badVerify = post("/api/v1/transactions/invariants/verify", Map.of(
                "transactionId", bad.toString()
        ), null).getBody();
        assertThat(list(badVerify, "results")).anySatisfy(result ->
                assertThat(((Map<?, ?>) result).get("status")).isEqualTo("FAIL"));
        assertThat(get("/api/v1/transactions/" + bad).getBody().get("status")).isEqualTo("SHIPPED");
    }
}
