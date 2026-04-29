package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinalCapstoneInvariantRegressionIntegrationTest extends Tg221To240IntegrationTestSupport {

    @Test
    void capstoneInvariantsRemainEnforcedAcrossTrustGridControlPlane() {
        var zeroMonitor = post("/api/v1/trust-monitors/run", Map.of(
                "requestedBy", "operator@example.com", "reason", "Final invariant zero state"), null);
        assertThat(zeroMonitor.getBody().toString()).contains("severity=LOW");
        assertThat(countRows("select count(*) from trust_incidents")).isZero();

        createCapabilityPolicy("ACCEPT_TRANSACTION", Map.of());
        createCapabilityPolicy("REQUEST_PAYMENT_RELEASE", Map.of());
        Flow flow = createCompletedServiceFlow("final-invariant");
        UUID reviewId = review(flow.transactionId(), flow.buyerId(), flow.providerId(), 5,
                "Final invariant review", "final-review-" + suffix());
        post("/api/v1/ops/moderator-actions/suppress-review-weight", Map.of(
                "targetType", "REVIEW",
                "targetId", reviewId.toString(),
                "actor", "operator@example.com",
                "reason", "Suppress suspicious review"
        ), null);
        post("/api/v1/participants/" + flow.providerId() + "/reputation/recalculate",
                Map.of("actor", "operator@example.com", "reason", "Final invariant recalculation"), null);
        post("/api/v1/lineage/rebuild/full", operator(), null);
        assertThat(countRows("select count(*) from trust_score_lineage_entries where source_id = ? and contribution_value = 0", reviewId))
                .isEqualTo(1);

        updateStatus(flow.providerId(), "SUSPENDED");
        assertThat(simulateCapability(flow.providerId(), "ACCEPT_TRANSACTION", null, null, 2500L).toString())
                .contains("ACCOUNT_SUSPENDED");
        updateStatus(flow.providerId(), "ACTIVE");
        restrict(flow.providerId(), "ACCEPTING_BLOCKED");
        assertThat(simulateCapability(flow.providerId(), "ACCEPT_TRANSACTION", null, null, 2500L).toString())
                .contains("CAPABILITY_RESTRICTED");

        UUID grant = temporaryGrant(flow.providerId(), "ACCEPT_TRANSACTION", null, null, future());
        jdbcTemplate.update("update temporary_capability_grants set expires_at = now() - interval '1 minute' where id = ?", grant);
        post("/api/v1/capability-governance/temporary-grants/expire", Map.of(), null);
        assertThat(simulateCapability(flow.providerId(), "ACCEPT_TRANSACTION", null, null, 2500L).get("decision"))
                .isNotEqualTo("ALLOW_WITH_TEMPORARY_GRANT");

        UUID breakGlass = breakGlass(flow.providerId(), "ACCEPT_TRANSACTION", null, null, future());
        assertThat(simulateCapability(flow.providerId(), "ACCEPT_TRANSACTION", null, null, 2500L).get("decision"))
                .isEqualTo("ALLOW_WITH_BREAK_GLASS");
        jdbcTemplate.update("update break_glass_capability_actions set expires_at = now() - interval '1 minute' where id = ?", breakGlass);
        post("/api/v1/capability-governance/break-glass/expire", Map.of(), null);

        var release = post("/api/v1/transactions/" + flow.transactionId() + "/payment-boundary/request-release",
                Map.of("actor", "operator@example.com", "reason", "Completed transaction release request"), null);
        assertThat(release.getStatusCode().is2xxSuccessful()).isTrue();
        Flow open = createDisputableServiceFlow("final-dispute");
        var invalidRelease = post("/api/v1/transactions/" + open.transactionId() + "/payment-boundary/request-release",
                Map.of("actor", "operator@example.com", "reason", "Invalid release"), null);
        assertThat(invalidRelease.getStatusCode().value()).isEqualTo(409);
        var invalidHold = post("/api/v1/transactions/" + flow.transactionId() + "/payment-boundary/request-payout-hold",
                Map.of("actor", "operator@example.com", "reason", "Invalid hold"), null);
        assertThat(invalidHold.getStatusCode().value()).isEqualTo(409);

        UUID rankingDecisionId = UUID.fromString(get("/api/v1/listings/trust-ranked-search?query=service&policyVersion=TRUST_BALANCED_V1")
                .getBody().get("rankingDecisionId").toString());
        var firstReplay = post("/api/v1/listings/ranking-decisions/" + rankingDecisionId + "/replay", Map.of(), null);
        var secondReplay = post("/api/v1/listings/ranking-decisions/" + rankingDecisionId + "/replay", Map.of(), null);
        assertThat(secondReplay.getBody().toString()).isEqualTo(firstReplay.getBody().toString());

        jdbcTemplate.update("update trust_profiles set trust_tier = 'HIGH_TRUST', trust_score = 900 where participant_id = ?", flow.providerId());
        post("/api/v1/consistency/checks/full", operator(), null);
        int openFindings = countRows("select count(*) from consistency_findings where status = 'OPEN'");
        post("/api/v1/consistency/checks/full", operator(), null);
        assertThat(countRows("select count(*) from consistency_findings where status = 'OPEN'")).isEqualTo(openFindings);
        post("/api/v1/data-repair/recommendations/generate", Map.of(), null);
        UUID recommendation = firstIdFromList("/api/v1/data-repair/recommendations", "id");
        var missingAck = post("/api/v1/data-repair/recommendations/" + recommendation + "/apply",
                Map.of("actor", "operator@example.com", "reason", "Missing acknowledgement"), null);
        assertThat(missingAck.getStatusCode().value()).isEqualTo(400);

        assertThat(countRows("select count(*) from information_schema.tables where table_schema = 'public' and table_name like '%ledger%'"))
                .isZero();
    }
}
