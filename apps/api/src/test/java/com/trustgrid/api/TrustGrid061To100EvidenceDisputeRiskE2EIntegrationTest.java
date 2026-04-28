package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustGrid061To100EvidenceDisputeRiskE2EIntegrationTest extends EvidenceDisputeRiskIntegrationTestSupport {

    @Test
    void evidenceDisputeReviewReputationRiskRankingAndHealthWorkEndToEnd() {
        Flow flow = createDisputableServiceFlow("e2e-061");
        UUID unrelated = createCapableParticipant("e2e-unrelated-" + suffix(), "Unrelated", "BUY");
        assertThat(post("/api/v1/transactions/" + flow.transactionId() + "/claim-completion",
                action(flow.buyerId()), "e2e-wrong-claim-" + suffix()).getStatusCode().value()).isEqualTo(403);
        assertThat(post("/api/v1/transactions/" + flow.transactionId() + "/cancel",
                action(unrelated), "e2e-wrong-cancel-" + suffix()).getStatusCode().value()).isEqualTo(403);

        UUID evidenceId = recordEvidence("TRANSACTION", flow.transactionId(), flow.providerId(), "SERVICE_COMPLETION_NOTE", "e2e-evidence-" + suffix());
        assertThat(evidenceId).isNotNull();
        UUID disputeId = openDispute(flow, "e2e-dispute-" + suffix());
        post("/api/v1/disputes/" + disputeId + "/statements", Map.of(
                "participantId", flow.buyerId().toString(),
                "statementType", "BUYER_STATEMENT",
                "statementText", "I need moderator review.",
                "actor", "buyer@example.com",
                "reason", "Buyer statement"
        ), null);
        post("/api/v1/disputes/" + disputeId + "/statements", Map.of(
                "participantId", flow.providerId().toString(),
                "statementType", "PROVIDER_STATEMENT",
                "statementText", "Completion evidence was recorded.",
                "actor", "provider@example.com",
                "reason", "Provider statement"
        ), null);
        Map<?, ?> bundle = get("/api/v1/disputes/" + disputeId + "/evidence-bundle").getBody();
        assertThat(bundle.keySet().toString()).contains("transactionTimeline");

        UUID statementEvidence = recordEvidence("DISPUTE", disputeId, flow.buyerId(), "USER_STATEMENT", "e2e-statement-" + suffix());
        Map<?, ?> requirement = (Map<?, ?>) getList("/api/v1/evidence-requirements?targetType=DISPUTE&targetId=" + disputeId).getBody().getFirst();
        post("/api/v1/evidence-requirements/" + requirement.get("requirementId") + "/satisfy", Map.of(
                "evidenceId", statementEvidence.toString(),
                "actor", "participant@example.com",
                "reason", "Satisfied"
        ), "e2e-satisfy-" + suffix());
        post("/api/v1/disputes/" + disputeId + "/resolve", Map.of(
                "outcome", "SPLIT_DECISION",
                "resolvedBy", "moderator@example.com",
                "reason", "Mixed evidence"
        ), null);

        post("/api/v1/transactions/" + flow.transactionId() + "/confirm-completion",
                action(flow.buyerId()), "e2e-confirm-" + suffix());
        assertThat(post("/api/v1/transactions/" + flow.transactionId() + "/reviews", Map.of(
                "reviewerParticipantId", flow.buyerId().toString(),
                "reviewedParticipantId", flow.providerId().toString(),
                "overallRating", 4,
                "reason", "Completed transaction review"
        ), "e2e-review-" + suffix()).getStatusCode().is2xxSuccessful()).isTrue();
        Map<?, ?> reputation = get("/api/v1/participants/" + flow.providerId() + "/reputation").getBody();
        assertThat(reputation.get("trustScore")).isNotNull();

        post("/api/v1/transactions/" + flow.transactionId() + "/off-platform-contact-reports", Map.of(
                "reporterParticipantId", flow.buyerId().toString(),
                "reportedParticipantId", flow.providerId().toString(),
                "reportText", "Risk explanation proof.",
                "reason", "Risk report"
        ), "e2e-risk-" + suffix());
        assertThat(get("/api/v1/risk/explain?targetType=TRANSACTION&targetId=" + flow.transactionId()).getBody().get("matchedRules").toString()).contains("off_platform");

        Map<?, ?> ranking = get("/api/v1/listings/trust-ranked-search?query=e2e&policyVersion=new_user_fairness_v1").getBody();
        UUID rankingDecisionId = UUID.fromString((String) ranking.get("rankingDecisionId"));
        assertThat(post("/api/v1/listings/ranking-decisions/" + rankingDecisionId + "/replay", Map.of(), null).getBody().get("matched")).isEqualTo(true);
        assertThat(countRows("select count(*) from marketplace_events where event_status = 'PENDING' and published_at is null")).isPositive();
        assertThat(get("/actuator/health").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/actuator/health/readiness").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/api/v1/system/ping").getBody().get("status")).isEqualTo("OK");
    }
}
