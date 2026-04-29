package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustRecoveryPlanIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void recoveryPlanMilestonesAndRestorationRecommendationWorkWithoutAutoRestore() {
        UUID participant = createCapableParticipant("recovery-" + suffix(), "Recovery", "BUY");
        UUID plan = createRecoveryPlan(participant);
        post("/api/v1/trust-recovery/plans/" + plan + "/milestones", Map.of("milestoneKey", "complete_verification", "status", "COMPLETE", "evaluatedBy", "operator@example.com", "reason", "Evaluate"), null);
        Map<?, ?> rec = post("/api/v1/trust-recovery/plans/" + plan + "/recommend-capability-restoration", Map.of("actor", "operator@example.com", "reason", "Recommend"), null).getBody();
        assertThat(rec.get("automaticRestore")).isEqualTo(false);
    }
}
