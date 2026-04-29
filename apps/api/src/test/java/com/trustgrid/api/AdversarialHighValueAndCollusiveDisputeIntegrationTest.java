package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdversarialHighValueAndCollusiveDisputeIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void highValueAndCollusiveDisputeRunsContributeToCoverageDashboard() {
        java.util.UUID highValue = runScenario("NEW_ACCOUNT_HIGH_VALUE_FRAUD");
        java.util.UUID collusive = runScenario("COLLUSIVE_DISPUTE_MANIPULATION");
        assertThat(countRows("select count(*) from detection_coverage_matrix where attack_run_id = ? and control_key = 'TRANSACTION_RISK_GATE_BLOCK' and detected = true", highValue)).isEqualTo(1);
        assertThat(countRows("select count(*) from detection_coverage_matrix where attack_run_id = ? and control_key = 'CAMPAIGN_GRAPH_EDGE_CREATED' and detected = true", collusive)).isEqualTo(1);
        assertThat(countRows("select count(*) from trust_campaign_graph_edges where source_id = ?", collusive)).isEqualTo(1);
        assertThat((Integer) get("/api/v1/adversarial/coverage-dashboard").getBody().get("attackRuns")).isGreaterThanOrEqualTo(2);
    }
}
