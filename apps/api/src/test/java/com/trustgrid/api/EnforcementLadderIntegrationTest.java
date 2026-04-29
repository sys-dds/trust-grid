package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnforcementLadderIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void enforcementPolicySimulationExecutionAndReversalWork() {
        createEnforcementPolicy();
        UUID participant = createCapableParticipant("enforce-" + suffix(), "Enforce", "BUY");
        assertThat(post("/api/v1/enforcement/simulate", Map.of("participantId", participant.toString(), "actionType", "SUSPEND_ACCOUNT"), null).getBody().get("requiresApproval")).isEqualTo(true);
        UUID action = UUID.fromString(post("/api/v1/enforcement/actions", Map.of("participantId", participant.toString(), "actionType", "SUSPEND_ACCOUNT", "actor", "operator@example.com", "reason", "Execute", "riskAcknowledgement", "ack"), null).getBody().get("actionId").toString());
        assertThat(post("/api/v1/enforcement/actions/" + action + "/reverse", actorRisk(), null).getBody().get("status")).isEqualTo("REVERSED");
    }
}
