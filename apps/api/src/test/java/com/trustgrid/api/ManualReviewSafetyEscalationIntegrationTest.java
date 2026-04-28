package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManualReviewSafetyEscalationIntegrationTest extends Tg101To160IntegrationTestSupport {

    @Test
    void manualReviewAndSafetyEscalationWorkflowsRecordEvents() {
        UUID participant = createCapableParticipant("manual-review-" + suffix(), "Manual Review", "BUY");
        Map<?, ?> reviewCase = post("/api/v1/ops/manual-review-cases", Map.of(
                "targetType", "PARTICIPANT", "targetId", participant.toString(),
                "actor", "operator@example.com", "reason", "Manual review"), null).getBody();
        post("/api/v1/ops/manual-review-cases/" + reviewCase.get("caseId") + "/status",
                Map.of("status", "ESCALATED", "actor", "operator@example.com", "reason", "Escalate"), null);
        Map<?, ?> escalation = post("/api/v1/ops/safety-escalations", Map.of(
                "targetType", "PARTICIPANT", "targetId", participant.toString(),
                "severity", "HIGH", "actor", "operator@example.com", "reason", "Safety review"), null).getBody();
        post("/api/v1/ops/safety-escalations/" + escalation.get("escalationId") + "/status",
                Map.of("status", "INVESTIGATING", "actor", "operator@example.com", "reason", "Investigate"), null);
        assertThat(getList("/api/v1/ops/manual-review-cases").getBody().toString()).contains("ESCALATED");
        assertThat(getList("/api/v1/ops/safety-escalations").getBody().toString()).contains("INVESTIGATING");
    }
}
