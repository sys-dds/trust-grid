package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustIncidentLifecycleIntegrationTest extends Tg181To220IntegrationTestSupport {

    @Test
    void incidentLifecycleTimelineImpactBundleAndMetricsWork() {
        UUID incident = createIncident();
        for (String status : new String[]{"INVESTIGATING", "MITIGATED", "RESOLVED"}) {
            var response = post("/api/v1/trust-incidents/" + incident + "/status", Map.of(
                    "status", status,
                    "actor", "operator@example.com",
                    "reason", "Move incident to " + status
            ), null);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        }

        assertThat(getList("/api/v1/trust-incidents/" + incident + "/timeline").getBody()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(get("/api/v1/trust-incidents/" + incident + "/impact").getBody()).containsKey("users_affected");
        assertThat(get("/api/v1/trust-incidents/" + incident + "/evidence-bundle").getBody().toString())
                .contains("incident", "timeline", "riskDecisions", "reviewAbuseClusters");
        assertThat(get("/api/v1/trust-incidents/metrics").getBody().toString()).contains("resolvedCount");
        assertThat(countRows("select count(*) from marketplace_events where event_type in ('TRUST_INCIDENT_CREATED','TRUST_INCIDENT_UPDATED','TRUST_INCIDENT_RESOLVED')"))
                .isGreaterThanOrEqualTo(3);
    }
}
