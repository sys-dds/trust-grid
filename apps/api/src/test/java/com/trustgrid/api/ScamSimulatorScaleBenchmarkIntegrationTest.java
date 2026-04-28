package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ScamSimulatorScaleBenchmarkIntegrationTest extends Tg101To160IntegrationTestSupport {

    @Test
    void simulatorsScaleCapsBenchmarkAndAbuseCampaignSummaryWork() {
        post("/api/v1/simulators/scam/v1", Map.of("simulationType", "FAKE_REVIEW_RING", "seedSize", 3,
                "requestedBy", "operator@example.com", "reason", "Simulator v1"), null);
        post("/api/v1/simulators/scam/v2", Map.of("simulationType", "MULTI_ACCOUNT_CAMPAIGN", "seedSize", 4,
                "requestedBy", "operator@example.com", "reason", "Simulator v2"), null);
        Map<?, ?> scale = post("/api/v1/simulators/seed-scale", Map.of(
                "participants", 100000, "listings", 1, "transactions", 1, "reviews", 1, "disputes", 1,
                "fraudClusters", 1, "requestedBy", "operator@example.com", "reason", "Scale proof"), null).getBody();
        assertThat(scale.toString()).contains("10000");
        post("/api/v1/benchmarks/run", Map.of("benchmarkType", "SEARCH_LATENCY",
                "requestedBy", "operator@example.com", "reason", "Benchmark"), null);
        assertThat(getList("/api/v1/abuse-campaigns").getBody().toString()).contains("SYNTHETIC_CLUSTER_SIGNAL");
        assertThat(countRows("select count(*) from scam_simulation_runs")).isGreaterThanOrEqualTo(3);
        assertThat(countRows("select count(*) from benchmark_runs")).isPositive();
    }
}
