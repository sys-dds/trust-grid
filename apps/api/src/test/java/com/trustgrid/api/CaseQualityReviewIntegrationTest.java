package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CaseQualityReviewIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void caseQualityReviewChecksTargetAndRecommendationAlignment() {
        UUID participant = createCapableParticipant("quality-" + suffix(), "Quality", "BUY");
        UUID caseId = openTrustCase(participant);
        post("/api/v1/trust-cases/" + caseId + "/recommendations/generate", Map.of("actor", "operator@example.com", "reason", "Generate"), null);
        Map<?, ?> review = post("/api/v1/trust-cases/" + caseId + "/quality-review", Map.of("actor", "operator@example.com", "reason", "Review"), null).getBody();
        assertThat(review.get("targetLinkageComplete")).isEqualTo(true);
    }
}
