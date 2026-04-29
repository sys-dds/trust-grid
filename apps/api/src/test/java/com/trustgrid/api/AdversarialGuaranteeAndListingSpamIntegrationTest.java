package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdversarialGuaranteeAndListingSpamIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void guaranteeAndListingSpamRunsSupportFalsePositiveWorkflow() {
        UUID guarantee = runScenario("GUARANTEE_ABUSE");
        UUID spam = runScenario("COORDINATED_LISTING_SPAM");
        assertThat(countRows("select count(*) from detection_coverage_matrix where attack_run_id = ? and control_key = 'GUARANTEE_FRAUD_EXCLUDED' and detected = true", guarantee)).isEqualTo(1);
        assertThat(countRows("select count(*) from guarantee_decision_logs where input_snapshot_json::text like ?", "%" + guarantee + "%")).isEqualTo(1);
        assertThat(countRows("select count(*) from detection_coverage_matrix where attack_run_id = ? and control_key = 'LISTING_SPAM_CLUSTER' and detected = true", spam)).isEqualTo(1);
        UUID review = UUID.fromString(post("/api/v1/adversarial/false-positive-reviews", Map.of("targetType", "ATTACK_RUN", "targetId", guarantee.toString(), "reportedBy", "operator@example.com", "reason", "Check false positive"), null).getBody().get("falsePositiveReviewId").toString());
        assertThat(post("/api/v1/adversarial/false-positive-reviews/" + review + "/decide", Map.of("decision", "CONFIRMED_SIGNAL", "actor", "operator@example.com", "reason", "Decide"), null).getBody().get("status")).isEqualTo("DECIDED");
    }
}
