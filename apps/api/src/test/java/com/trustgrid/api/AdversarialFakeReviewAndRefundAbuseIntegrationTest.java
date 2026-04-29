package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdversarialFakeReviewAndRefundAbuseIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void fakeReviewAndRefundAbuseRunsCreateCoverage() {
        UUID fake = runScenario("FAKE_REVIEW_FARMING");
        UUID refund = runScenario("REFUND_ABUSE");
        assertThat(getList("/api/v1/adversarial/attack-runs/" + fake + "/coverage").getBody()).isNotEmpty();
        assertThat(getList("/api/v1/adversarial/attack-runs/" + refund + "/defense-recommendations").getBody()).isNotEmpty();
    }
}
