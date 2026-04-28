package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TrustTelemetryAndSloMonitorIntegrationTest extends Tg181To220IntegrationTestSupport {

    @Test
    void telemetrySlosAndManualMonitorsCreateSignalsIncidentsAndAlertsWithoutSchedulers() {
        post("/api/v1/ops/queue/rebuild", actorReason(), null);
        var telemetry = post("/api/v1/trust-telemetry/record", Map.of(
                "telemetryType", "MODERATION_BACKLOG",
                "targetType", "MODERATION_BACKLOG",
                "severity", "MEDIUM",
                "signalValue", 1,
                "thresholdValue", 0,
                "payload", Map.of("source", "test")
        ), null);
        assertThat(telemetry.getStatusCode().is2xxSuccessful()).isTrue();

        var slo = post("/api/v1/trust-slos", Map.of(
                "sloKey", "moderation-backlog-proof",
                "name", "Moderation backlog proof",
                "targetType", "MODERATION_BACKLOG",
                "thresholdValue", 0,
                "windowMinutes", 60,
                "severity", "HIGH"
        ), null);
        assertThat(slo.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(post("/api/v1/trust-slos/evaluate", Map.of(), null).getBody().toString()).contains("breaches");

        var monitors = post("/api/v1/trust-monitors/run", Map.of(
                "requestedBy", "operator@example.com",
                "reason", "Manual trust monitor run",
                "windowMinutes", 60
        ), null);
        assertThat(monitors.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(monitors.getBody().toString()).contains("MODERATION_BACKLOG", "DISPUTE_BACKLOG",
                "RISK_SPIKE", "REVIEW_ABUSE_SPIKE", "TRUST_SCORE_SPIKE", "SEARCH_SUPPRESSION_SPIKE",
                "noBackgroundScheduler=true", "externalAlerting=false");
        assertThat(countRows("select count(*) from trust_telemetry_events")).isGreaterThanOrEqualTo(7);
        assertThat(countRows("select count(*) from trust_incidents")).isGreaterThanOrEqualTo(1);
        assertThat(countRows("select count(*) from trust_alerts")).isGreaterThanOrEqualTo(1);
    }
}
