package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewReputationEligibilityIntegrationTest extends EvidenceDisputeRiskIntegrationTestSupport {

    @Test
    void completedTransactionsAreReviewableOncePerSideAndUnresolvedDisputeBlocksReview() {
        Flow completed = createCompletedServiceFlow("review-ok");
        Map<?, ?> review = post("/api/v1/transactions/" + completed.transactionId() + "/reviews", Map.ofEntries(
                Map.entry("reviewerParticipantId", completed.buyerId().toString()),
                Map.entry("reviewedParticipantId", completed.providerId().toString()),
                Map.entry("overallRating", 5),
                Map.entry("accuracyRating", 5),
                Map.entry("reliabilityRating", 5),
                Map.entry("communicationRating", 4),
                Map.entry("punctualityRating", 5),
                Map.entry("evidenceQualityRating", 4),
                Map.entry("itemServiceMatchRating", 5),
                Map.entry("reviewText", "Great experience."),
                Map.entry("reason", "Completed transaction review")
        ), "review-create-" + suffix()).getBody();
        assertThat(review.get("confidenceWeight")).isNotNull();
        assertThat(post("/api/v1/transactions/" + completed.transactionId() + "/reviews", Map.of(
                "reviewerParticipantId", completed.buyerId().toString(),
                "reviewedParticipantId", completed.providerId().toString(),
                "overallRating", 5,
                "reason", "Duplicate review"
        ), "review-duplicate-" + suffix()).getStatusCode().value()).isEqualTo(409);

        Flow disputed = createDisputableServiceFlow("review-blocked");
        UUID disputeId = openDispute(disputed, "review-blocked-open-" + suffix());
        assertThat(disputeId).isNotNull();
        jdbcTemplate.update("update marketplace_transactions set status = 'COMPLETED', completed_at = now() where id = ?", disputed.transactionId());
        assertThat(post("/api/v1/transactions/" + disputed.transactionId() + "/reviews", Map.of(
                "reviewerParticipantId", disputed.buyerId().toString(),
                "reviewedParticipantId", disputed.providerId().toString(),
                "overallRating", 4,
                "reason", "Blocked review"
        ), "review-blocked-" + suffix()).getStatusCode().value()).isEqualTo(409);
    }
}
