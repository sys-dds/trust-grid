package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

abstract class Tg101To160IntegrationTestSupport extends EvidenceDisputeRiskIntegrationTestSupport {

    Flow createCompletedServiceFlowBetween(UUID buyer, UUID provider, String prefix) {
        UUID listing = createListing(provider, "SERVICE_OFFER", "TUTORING", prefix + " service " + suffix(), 2500L, null, serviceDetails());
        publish(listing);
        UUID tx = createTransaction(listing, buyer, provider, prefix + "-tx-" + suffix());
        post("/api/v1/transactions/" + tx + "/start", action(provider), prefix + "-start-" + suffix());
        post("/api/v1/transactions/" + tx + "/claim-completion", action(provider), prefix + "-claim-" + suffix());
        post("/api/v1/transactions/" + tx + "/confirm-completion", action(buyer), prefix + "-confirm-" + suffix());
        return new Flow(buyer, provider, listing, tx);
    }

    UUID review(UUID transactionId, UUID reviewer, UUID reviewed, int rating, String text, String key) {
        ResponseEntity<Map> response = post("/api/v1/transactions/" + transactionId + "/reviews", Map.ofEntries(
                Map.entry("reviewerParticipantId", reviewer.toString()),
                Map.entry("reviewedParticipantId", reviewed.toString()),
                Map.entry("overallRating", rating),
                Map.entry("accuracyRating", rating),
                Map.entry("reliabilityRating", rating),
                Map.entry("communicationRating", rating),
                Map.entry("punctualityRating", rating),
                Map.entry("evidenceQualityRating", rating),
                Map.entry("itemServiceMatchRating", rating),
                Map.entry("reviewText", text),
                Map.entry("reason", "Completed transaction review")
        ), key);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString((String) response.getBody().get("reviewId"));
    }

    UUID firstCluster() {
        java.util.List body = getList("/api/v1/review-graph/clusters").getBody();
        assertThat(body).isNotEmpty();
        Map<?, ?> cluster = (Map<?, ?>) body.getFirst();
        return UUID.fromString((String) cluster.get("clusterId"));
    }

    Map<String, Object> actorReason() {
        return Map.of("actor", "operator@example.com", "reason", "Operator proof");
    }
}
