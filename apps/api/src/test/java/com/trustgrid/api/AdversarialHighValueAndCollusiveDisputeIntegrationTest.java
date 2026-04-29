package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdversarialHighValueAndCollusiveDisputeIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void highValueAndCollusiveDisputeRunsContributeToCoverageDashboard() {
        runScenario("NEW_ACCOUNT_HIGH_VALUE_FRAUD");
        runScenario("COLLUSIVE_DISPUTE_MANIPULATION");
        assertThat((Integer) get("/api/v1/adversarial/coverage-dashboard").getBody().get("attackRuns")).isGreaterThanOrEqualTo(2);
    }
}
