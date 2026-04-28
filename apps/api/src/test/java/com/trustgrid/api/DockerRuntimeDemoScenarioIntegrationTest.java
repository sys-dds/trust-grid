package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DockerRuntimeDemoScenarioIntegrationTest extends Tg101To160IntegrationTestSupport {

    @Test
    void coreRuntimeSmokeEndpointsAreAvailable() {
        assertThat(get("/actuator/health").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/actuator/health/readiness").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/api/v1/system/ping").getBody().get("status")).isEqualTo("OK");
        assertThat(get("/api/v1/system/node").getBody().get("service")).isEqualTo("trust-grid-api");
        assertThat(getList("/api/v1/categories").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(getList("/api/v1/ops/queue").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(getList("/api/v1/policies").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/api/v1/analytics/marketplace-health").getBody().get("analyticsBackend")).isEqualTo("POSTGRES_FALLBACK");
    }
}
