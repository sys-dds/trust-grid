package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdversarialGuaranteeAndListingSpamIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void guaranteeAndListingSpamRunsSupportFalsePositiveWorkflow() {
        UUID guarantee = runScenario("GUARANTEE_ABUSE");
        runScenario("COORDINATED_LISTING_SPAM");
        UUID review = UUID.fromString(post("/api/v1/adversarial/false-positive-reviews", Map.of("targetType", "ATTACK_RUN", "targetId", guarantee.toString(), "reportedBy", "operator@example.com", "reason", "Check false positive"), null).getBody().get("falsePositiveReviewId").toString());
        assertThat(post("/api/v1/adversarial/false-positive-reviews/" + review + "/decide", Map.of("decision", "CONFIRMED_SIGNAL", "actor", "operator@example.com", "reason", "Decide"), null).getBody().get("status")).isEqualTo("DECIDED");
    }
}
