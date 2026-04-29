package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdversarialScenarioCatalogIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void scenarioCatalogRecordsExpectedControls() {
        createScenario("FAKE_REVIEW_FARMING");
        assertThat(getList("/api/v1/adversarial/scenarios").getBody().toString()).contains("FAKE_REVIEW_FARMING", "expected_controls_json");
    }
}
