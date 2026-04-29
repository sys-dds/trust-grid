package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdversarialEvidenceAndOffPlatformAbuseIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void evidenceTamperingAndOffPlatformRunsReplayDeterministically() {
        UUID evidence = runScenario("EVIDENCE_TAMPERING");
        runScenario("OFF_PLATFORM_PAYMENT_PRESSURE");
        assertThat(post("/api/v1/adversarial/attack-runs/" + evidence + "/replay", java.util.Map.of(), null).getBody().get("deterministic")).isEqualTo(true);
    }
}
