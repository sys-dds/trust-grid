package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IncidentReplayIntegrationTest extends Tg181To220IntegrationTestSupport {

    @Test
    void incidentReplayIsDeterministicAndRecordsReplayEvent() {
        UUID incident = createIncident();
        var replay1 = post("/api/v1/trust-incidents/" + incident + "/replay", java.util.Map.of(), null);
        var replay2 = post("/api/v1/trust-incidents/" + incident + "/replay", java.util.Map.of(), null);
        assertThat(replay1.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(replay1.getBody().get("replayedIncidentType")).isEqualTo(replay2.getBody().get("replayedIncidentType"));
        assertThat(replay1.getBody().get("replayedSeverity")).isEqualTo(replay2.getBody().get("replayedSeverity"));
        assertThat(replay1.getBody().get("matchedOriginal")).isEqualTo(true);
        assertThat(countRows("select count(*) from marketplace_events where event_type = 'TRUST_INCIDENT_REPLAYED'"))
                .isGreaterThanOrEqualTo(2);
    }
}
