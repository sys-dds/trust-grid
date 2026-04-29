package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdversarialFakeReviewAndRefundAbuseIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void fakeReviewAndRefundAbuseRunsCreateScenarioSpecificCoverage() {
        UUID fake = runScenario("FAKE_REVIEW_FARMING");
        UUID refund = runScenario("REFUND_ABUSE");
        assertThat(countRows("select count(*) from detection_coverage_matrix where attack_run_id = ? and control_key = 'REVIEW_ABUSE_CLUSTER_DETECTED' and detected = true", fake)).isEqualTo(1);
        assertThat(countRows("select count(*) from review_abuse_clusters where signals_json::text like ?", "%attackRun:" + fake + "%")).isEqualTo(1);
        assertThat(countRows("select count(*) from detection_coverage_matrix where attack_run_id = ? and control_key = 'RISK_DECISION_RECORDED' and detected = true", refund)).isEqualTo(1);
        assertThat(countRows("select count(*) from risk_decisions where snapshot_json::text like ?", "%" + refund + "%")).isEqualTo(1);
        assertThat(getList("/api/v1/adversarial/attack-runs/" + fake + "/coverage").getBody().toString())
                .isNotEqualTo(getList("/api/v1/adversarial/attack-runs/" + refund + "/coverage").getBody().toString());
    }
}
