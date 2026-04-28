package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentBoundaryEventIntegrationTest extends Tg101To160IntegrationTestSupport {

    @Test
    void paymentBoundaryEventsAreControlPlaneOnly() {
        Flow completed = createCompletedServiceFlow("payment-boundary");
        post("/api/v1/transactions/" + completed.transactionId() + "/payment-boundary/request-release", actorReason(), null);
        Flow disputed = createDisputableServiceFlow("payment-refund");
        UUID dispute = openDispute(disputed, "payment-dispute-" + suffix());
        post("/api/v1/disputes/" + dispute + "/resolve", Map.of(
                "outcome", "SAFETY_ESCALATION",
                "resolvedBy", "moderator@example.com",
                "reason", "Boundary proof"), null);
        post("/api/v1/transactions/" + disputed.transactionId() + "/payment-boundary/request-refund", actorReason(), null);
        jdbcTemplate.update("""
                insert into risk_decisions (id, target_type, target_id, score, risk_level, decision, policy_version)
                values (?, 'PARTICIPANT', ?, 90, 'HIGH', 'REQUIRE_MANUAL_REVIEW', 'risk_rules_v1')
                """, UUID.randomUUID(), disputed.providerId());
        post("/api/v1/transactions/" + disputed.transactionId() + "/payment-boundary/request-payout-hold", actorReason(), null);
        assertThat(get("/api/v1/transactions/" + completed.transactionId() + "/payment-boundary").getBody().toString()).contains("RELEASE_REQUESTED");
        assertThat(countRows("select count(*) from payment_boundary_events")).isGreaterThanOrEqualTo(3);
        assertThat(countRows("select count(*) from information_schema.tables where table_name like '%balance%'")).isZero();
    }
}
