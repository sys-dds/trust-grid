package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinalCoreInvariantProofIntegrationTest extends Tg101To160IntegrationTestSupport {

    @Test
    void coreInvariantsHoldAcrossSearchDisputesPaymentReputationReplayAndPolicy() {
        Flow flow = createCompletedServiceFlow("final-invariant");
        int snapshotsBefore = countRows("select count(*) from reputation_snapshots where participant_id = ?", flow.providerId());
        get("/api/v1/participants/" + flow.providerId() + "/reputation");
        assertThat(countRows("select count(*) from reputation_snapshots where participant_id = ?", flow.providerId())).isEqualTo(snapshotsBefore);
        post("/api/v1/participants/" + flow.providerId() + "/reputation/recalculate",
                Map.of("actor", "system", "reason", "Explicit invariant recalculation"), null);
        assertThat(countRows("select count(*) from reputation_snapshots where participant_id = ?", flow.providerId())).isGreaterThan(snapshotsBefore);

        post("/api/v1/ops/moderator-actions/hide-listing", Map.of(
                "targetType", "LISTING", "targetId", flow.listingId().toString(),
                "actor", "moderator@example.com", "reason", "Invariant hide"), null);
        assertThat(get("/api/v1/listings/search?query=final-invariant").getBody().toString()).doesNotContain(flow.listingId().toString());

        UUID dispute = openDispute(flow, "final-dispute-" + suffix());
        assertThat(post("/api/v1/disputes/" + dispute + "/resolve", Map.of(
                "outcome", "BUYER_WINS", "resolvedBy", "moderator@example.com", "reason", "Missing evidence"), null)
                .getStatusCode().value()).isEqualTo(409);
        post("/api/v1/disputes/" + dispute + "/resolve", Map.of(
                "outcome", "INSUFFICIENT_EVIDENCE", "resolvedBy", "moderator@example.com", "reason", "Allowed outcome"), null);
        post("/api/v1/transactions/" + flow.transactionId() + "/payment-boundary/request-release", actorReason(), null);
        post("/api/v1/transactions/" + flow.transactionId() + "/payment-boundary/request-refund", Map.of(
                "actor", "operator@example.com", "reason", "refund recommended after insufficient evidence"), null);
        assertThat(countRows("select count(*) from payment_boundary_events")).isGreaterThanOrEqualTo(2);

        Map<?, ?> ranking = get("/api/v1/listings/trust-ranked-search?policyVersion=risk_averse_v1").getBody();
        UUID rankingDecisionId = UUID.fromString((String) ranking.get("rankingDecisionId"));
        assertThat(post("/api/v1/listings/ranking-decisions/" + rankingDecisionId + "/replay", Map.of(), null).getBody().get("matched")).isEqualTo(true);
        post("/api/v1/policy-simulations/trust", Map.of("toPolicyVersion", "strict_v1", "requestedBy", "operator@example.com", "reason", "No mutation"), null);
        post("/api/v1/replay/outbox", actorReason(), null);
        post("/api/v1/rebuilds/search-index", actorReason(), null);
        post("/api/v1/consistency/evidence/verify", actorReason(), null);
        assertThat(get("/api/v1/system/ping").getBody().get("status")).isEqualTo("OK");
    }
}
