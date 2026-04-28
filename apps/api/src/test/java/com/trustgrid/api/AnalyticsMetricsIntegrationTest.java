package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalyticsMetricsIntegrationTest extends Tg101To160IntegrationTestSupport {

    @Test
    void analyticsIngestionAndMetricsUsePostgresFallback() {
        createCompletedServiceFlow("analytics");
        post("/api/v1/analytics/ingest-events", Map.of(), null);
        assertThat(get("/api/v1/analytics/trust-metrics").getBody().get("analyticsBackend")).isEqualTo("POSTGRES_FALLBACK");
        assertThat(get("/api/v1/analytics/fraud-metrics").getBody().get("policyVersion")).isEqualTo("deterministic_rules_v1");
        assertThat(get("/api/v1/analytics/marketplace-health").getBody().get("analyticsBackend")).isEqualTo("POSTGRES_FALLBACK");
        assertThat(get("/api/v1/analytics/ranking").getBody().get("analyticsBackend")).isEqualTo("POSTGRES_FALLBACK");
        assertThat(get("/api/v1/analytics/disputes").getBody().get("analyticsBackend")).isEqualTo("POSTGRES_FALLBACK");
    }
}
