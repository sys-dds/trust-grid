package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewGraphAbuseDetectionIntegrationTest extends Tg101To160IntegrationTestSupport {

    @Test
    void reviewGraphDetectsReciprocalRingLowValueSimilarTextAndBurstClusters() {
        UUID a = createCapableParticipant("graph-a-" + suffix(), "Graph A", "BUY", "OFFER_SERVICES");
        UUID b = createCapableParticipant("graph-b-" + suffix(), "Graph B", "BUY", "OFFER_SERVICES");
        UUID c = createCapableParticipant("graph-c-" + suffix(), "Graph C", "BUY", "OFFER_SERVICES");
        Flow ab = createCompletedServiceFlowBetween(a, b, "graph-ab");
        Flow ba = createCompletedServiceFlowBetween(b, a, "graph-ba");
        Flow ca = createCompletedServiceFlowBetween(c, a, "graph-ca");
        UUID lowListing = createListing(b, "SERVICE_OFFER", "TUTORING", "low value graph " + suffix(), 500L, null, serviceDetails());
        publish(lowListing);
        UUID lowTx = createTransaction(lowListing, c, b, "graph-low-" + suffix());
        post("/api/v1/transactions/" + lowTx + "/start", action(b), "graph-low-start-" + suffix());
        post("/api/v1/transactions/" + lowTx + "/claim-completion", action(b), "graph-low-claim-" + suffix());
        post("/api/v1/transactions/" + lowTx + "/confirm-completion", action(c), "graph-low-confirm-" + suffix());
        review(ab.transactionId(), a, b, 5, "Excellent local help", "review-ab-" + suffix());
        review(ba.transactionId(), b, a, 5, "Excellent local help", "review-ba-" + suffix());
        review(ca.transactionId(), c, a, 5, "Excellent local help", "review-ca-" + suffix());
        review(lowTx, c, b, 5, "Excellent local help", "review-low-" + suffix());

        post("/api/v1/review-graph/rebuild", java.util.Map.of(), null);
        java.util.List clusters = getList("/api/v1/review-graph/clusters").getBody();
        assertThat(clusters.toString()).contains("RECIPROCAL_REVIEWS", "REVIEW_RING", "SIMILAR_REVIEW_TEXT", "REVIEW_BURST", "LOW_VALUE_REVIEW_FARMING");
        assertThat(countRows("select count(*) from review_graph_edges")).isGreaterThanOrEqualTo(3);
    }
}
