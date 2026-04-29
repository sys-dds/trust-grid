package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarketplaceGuaranteePolicyIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void guaranteeEligibilityUsesDomainSignalsAndIdempotency() {
        String policyName = "guarantee_policy_" + suffix();
        post("/api/v1/marketplace-guarantees/policies", Map.of(
                "policyName", policyName,
                "policyVersion", "v1",
                "maxValueCents", 10000,
                "requiredEvidence", List.of("BEFORE_PHOTO"),
                "createdBy", "operator@example.com",
                "reason", "Guarantee policy proof"
        ), null);
        Flow flow = createCompletedServiceFlow("guarantee-" + suffix());
        recordEvidence("TRANSACTION", flow.transactionId(), flow.buyerId(), "BEFORE_PHOTO", "guarantee-evidence-" + suffix());
        Map<?, ?> eligible = post("/api/v1/marketplace-guarantees/eligibility-simulate", Map.of(
                "transactionId", flow.transactionId().toString(),
                "participantId", flow.buyerId().toString(),
                "policyName", policyName,
                "policyVersion", "v1",
                "idempotencyKey", "guarantee-idem-" + suffix()
        ), null).getBody();
        UUID decision = UUID.fromString(eligible.get("decisionId").toString());
        assertThat(get("/api/v1/marketplace-guarantees/decisions/" + decision).getBody().get("decision")).isEqualTo("ELIGIBLE");
        assertThat(getList("/api/v1/marketplace-guarantees/decisions/" + decision + "/timeline").getBody()).isNotEmpty();

        Flow missingEvidence = createCompletedServiceFlow("guarantee-missing-" + suffix());
        assertThat(post("/api/v1/marketplace-guarantees/eligibility-simulate", Map.of(
                "transactionId", missingEvidence.transactionId().toString(),
                "participantId", missingEvidence.buyerId().toString(),
                "policyName", policyName,
                "policyVersion", "v1"
        ), null).getBody().get("decision")).isEqualTo("NEEDS_EVIDENCE");

        UUID tamperedEvidence = recordEvidence("TRANSACTION", flow.transactionId(), flow.buyerId(), "AFTER_PHOTO", "guarantee-tamper-" + suffix());
        post("/api/v1/evidence/" + tamperedEvidence + "/versions", Map.of("hash", "expected", "actor", "operator@example.com", "reason", "Version"), null);
        post("/api/v1/evidence/" + tamperedEvidence + "/tamper-check", Map.of("expectedHash", "bad", "actor", "operator@example.com", "reason", "Tamper"), null);
        assertThat(post("/api/v1/marketplace-guarantees/eligibility-simulate", Map.of(
                "transactionId", flow.transactionId().toString(),
                "participantId", flow.buyerId().toString(),
                "policyName", policyName,
                "policyVersion", "v1",
                "fraudSignal", true
        ), null).getBody().get("decision")).isEqualTo("FRAUD_EXCLUDED");

        Flow unresolved = createDisputableServiceFlow("guarantee-unresolved-" + suffix());
        UUID dispute = openDispute(unresolved, "guarantee-unresolved-" + suffix());
        recordEvidence("TRANSACTION", unresolved.transactionId(), unresolved.buyerId(), "BEFORE_PHOTO", "guarantee-unresolved-evidence-" + suffix());
        assertThat(post("/api/v1/marketplace-guarantees/eligibility-simulate", Map.of(
                "transactionId", unresolved.transactionId().toString(),
                "disputeId", dispute.toString(),
                "participantId", unresolved.buyerId().toString(),
                "policyName", policyName,
                "policyVersion", "v1"
        ), null).getBody().get("decision")).isEqualTo("NOT_ELIGIBLE");

        Flow highValue = createCompletedServiceFlow("guarantee-high-" + suffix());
        jdbcTemplate.update("update marketplace_transactions set value_amount_cents = 50000 where id = ?", highValue.transactionId());
        recordEvidence("TRANSACTION", highValue.transactionId(), highValue.buyerId(), "BEFORE_PHOTO", "guarantee-high-evidence-" + suffix());
        assertThat(post("/api/v1/marketplace-guarantees/eligibility-simulate", Map.of(
                "transactionId", highValue.transactionId().toString(),
                "participantId", highValue.buyerId().toString(),
                "policyName", policyName,
                "policyVersion", "v1"
        ), null).getBody().get("decision")).isEqualTo("NOT_ELIGIBLE");

        String key = "guarantee-same-key-" + suffix();
        Map<?, ?> first = post("/api/v1/marketplace-guarantees/eligibility-simulate", Map.of(
                "transactionId", flow.transactionId().toString(),
                "participantId", flow.buyerId().toString(),
                "policyName", policyName,
                "policyVersion", "v1",
                "idempotencyKey", key
        ), null).getBody();
        Map<?, ?> second = post("/api/v1/marketplace-guarantees/eligibility-simulate", Map.of(
                "transactionId", flow.transactionId().toString(),
                "participantId", flow.buyerId().toString(),
                "policyName", policyName,
                "policyVersion", "v1",
                "idempotencyKey", key
        ), null).getBody();
        assertThat(second.get("decisionId")).isEqualTo(first.get("decisionId"));
        assertThat(post("/api/v1/marketplace-guarantees/eligibility-simulate", Map.of(
                "transactionId", highValue.transactionId().toString(),
                "participantId", highValue.buyerId().toString(),
                "policyName", policyName,
                "policyVersion", "v1",
                "idempotencyKey", key
        ), null).getStatusCode().value()).isEqualTo(409);
    }
}
