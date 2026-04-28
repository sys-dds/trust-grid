package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustGrid101To160CoreProofE2EIntegrationTest extends Tg101To160IntegrationTestSupport {

    @Test
    void fullControlPlaneProofWorksThroughPublicApis() {
        Flow flow = createCompletedServiceFlow("core-proof");
        review(flow.transactionId(), flow.buyerId(), flow.providerId(), 5, "Core proof review", "core-review-" + suffix());
        post("/api/v1/simulators/scam/v1", Map.of("simulationType", "FAKE_REVIEW_RING", "requestedBy", "operator@example.com", "reason", "Proof"), null);
        post("/api/v1/review-graph/rebuild", Map.of(), null);
        UUID cluster = firstCluster();
        post("/api/v1/review-graph/clusters/" + cluster + "/suppress-review-weight",
                Map.of("actor", "moderator@example.com", "reason", "Suppress suspicious review"), null);
        post("/api/v1/participants/" + flow.providerId() + "/reputation/recalculate",
                Map.of("actor", "system", "reason", "Post suppression"), null);
        post("/api/v1/ops/queue/rebuild", Map.of(), null);
        post("/api/v1/ops/moderator-actions/restrict-capability", Map.of(
                "targetType", "PARTICIPANT", "targetId", flow.providerId().toString(),
                "actor", "moderator@example.com", "reason", "Cluster confirmed"), null);

        UUID dispute = openDispute(flow, "core-dispute-" + suffix());
        UUID evidence = recordEvidence("DISPUTE", dispute, flow.buyerId(), "USER_STATEMENT", "core-statement-" + suffix());
        Map<?, ?> requirement = (Map<?, ?>) getList("/api/v1/evidence-requirements?targetType=DISPUTE&targetId=" + dispute).getBody().getFirst();
        post("/api/v1/evidence-requirements/" + requirement.get("requirementId") + "/satisfy", Map.of(
                "evidenceId", evidence.toString(), "actor", "participant@example.com", "reason", "Satisfied"), "core-satisfy-" + suffix());
        post("/api/v1/disputes/" + dispute + "/resolve", Map.of(
                "outcome", "SPLIT_DECISION", "resolvedBy", "moderator@example.com", "reason", "Core proof"), null);
        post("/api/v1/transactions/" + flow.transactionId() + "/payment-boundary/request-refund", actorReason(), null);
        post("/api/v1/analytics/ingest-events", Map.of(), null);
        post("/api/v1/rebuilds/reputation", actorReason(), null);
        post("/api/v1/rebuilds/search-index", actorReason(), null);
        post("/api/v1/replay/outbox", actorReason(), null);
        post("/api/v1/replay/audit-timeline", actorReason(), null);
        post("/api/v1/policy-simulations/shadow-risk", Map.of("toPolicyVersion", "strict_v1", "requestedBy", "operator@example.com", "reason", "Shadow"), null);
        Map<?, ?> appeal = post("/api/v1/participants/" + flow.providerId() + "/appeals", Map.of(
                "targetType", "PARTICIPANT", "targetId", flow.providerId().toString(), "appealReason", "Review restriction"), null).getBody();
        post("/api/v1/appeals/" + appeal.get("appealId") + "/decide", Map.of(
                "decision", "RESTRICTION_REDUCED", "decidedBy", "moderator@example.com", "reason", "Reduced"), null);
        Map<?, ?> ranking = get("/api/v1/listings/trust-ranked-search?policyVersion=new_user_fairness_v1").getBody();
        UUID rankingDecisionId = UUID.fromString((String) ranking.get("rankingDecisionId"));
        assertThat(post("/api/v1/listings/ranking-decisions/" + rankingDecisionId + "/replay", Map.of(), null).getBody().get("matched")).isEqualTo(true);
        assertThat(get("/actuator/health/readiness").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/api/v1/system/ping").getBody().get("status")).isEqualTo("OK");
    }
}
