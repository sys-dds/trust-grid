package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ModeratorQaPeerReviewIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void moderatorQaReviewAndMetricsWork() {
        assertThat(post("/api/v1/moderator-qa/reviews", Map.of("reviewer", "lead@example.com", "qaStatus", "PASS", "score", 95, "reason", "Peer review"), null).getBody().get("qaReviewId")).isNotNull();
        assertThat(get("/api/v1/moderator-qa/metrics").getBody().get("reviews")).isNotNull();
    }
}
