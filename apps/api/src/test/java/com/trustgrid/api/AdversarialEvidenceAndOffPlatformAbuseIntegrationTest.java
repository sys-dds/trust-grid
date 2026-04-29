package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdversarialEvidenceAndOffPlatformAbuseIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void evidenceTamperingAndOffPlatformRunsReplayDeterministically() {
        UUID evidence = runScenario("EVIDENCE_TAMPERING");
        UUID offPlatform = runScenario("OFF_PLATFORM_PAYMENT_PRESSURE");
        assertThat(countRows("select count(*) from detection_coverage_matrix where attack_run_id = ? and control_key = 'EVIDENCE_HASH_MISMATCH' and detected = true", evidence)).isEqualTo(1);
        assertThat(countRows("select count(*) from evidence_custody_events where event_type = 'HASH_MISMATCH_DETECTED' and metadata_json::text like ?", "%" + evidence + "%")).isEqualTo(1);
        assertThat(countRows("select count(*) from detection_coverage_matrix where attack_run_id = ? and control_key = 'OFF_PLATFORM_CONTACT_REPORTED' and detected = true", offPlatform)).isEqualTo(1);
        Map<?, ?> replay = post("/api/v1/adversarial/attack-runs/" + evidence + "/replay", java.util.Map.of(), null).getBody();
        assertThat(replay.get("deterministic")).isEqualTo(true);
        assertThat(replay.get("matchedOriginal")).isEqualTo(true);
    }
}
