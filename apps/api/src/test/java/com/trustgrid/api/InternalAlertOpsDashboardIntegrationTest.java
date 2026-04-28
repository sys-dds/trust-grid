package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InternalAlertOpsDashboardIntegrationTest extends Tg181To220IntegrationTestSupport {

    @Test
    void internalAlertsCanBeAcknowledgedAndDashboardAggregatesTrustControlRoom() {
        post("/api/v1/trust-monitors/run", Map.of(
                "requestedBy", "operator@example.com",
                "reason", "Generate internal alert",
                "windowMinutes", 60
        ), null);
        UUID alertId = firstIdFromList("/api/v1/trust-alerts", "id");
        var acknowledged = post("/api/v1/trust-alerts/" + alertId + "/acknowledge", Map.of(
                "actor", "operator@example.com",
                "reason", "Acknowledged in control room"
        ), null);
        assertThat(acknowledged.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(acknowledged.getBody().get("status")).isEqualTo("ACKNOWLEDGED");

        var dashboard = get("/api/v1/ops/dashboard/trust-control-room");
        assertThat(dashboard.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(dashboard.getBody().toString()).contains("openIncidents", "openAlerts", "moderationBacklog",
                "disputeBacklog", "evidenceBacklog", "paymentBoundaryReviewBacklog");
        assertThat(countRows("select count(*) from marketplace_events where event_type in ('TRUST_ALERT_ACKNOWLEDGED','OPS_DASHBOARD_AGGREGATED')"))
                .isGreaterThanOrEqualTo(2);
    }
}
