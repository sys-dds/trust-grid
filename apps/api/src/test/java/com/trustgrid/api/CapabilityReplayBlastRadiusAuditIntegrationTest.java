package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapabilityReplayBlastRadiusAuditIntegrationTest extends Tg221To240IntegrationTestSupport {

    @Test
    void replayBlastRadiusAndAuditBundleAreDeterministicAndScoped() {
        createCapabilityPolicy("ACCEPT_TRANSACTION", Map.of("maxValueCents", 1000));
        UUID allowed = createCapableParticipant("cap-replay-allow-" + suffix(), "Replay Allow", "OFFER_SERVICES");
        UUID denied = createCapableParticipant("cap-replay-deny-" + suffix(), "Replay Deny", "OFFER_SERVICES");

        Map<?, ?> decision = simulateCapability(denied, "ACCEPT_TRANSACTION", null, null, 5000L);
        UUID decisionId = UUID.fromString(decision.get("decisionId").toString());
        Map<?, ?> replay = post("/api/v1/capability-governance/replay/" + decisionId, Map.of(), null).getBody();
        assertThat(replay.get("matchedOriginal")).isEqualTo(true);
        assertThat(replay.get("deterministic")).isEqualTo(true);
        assertThat(replay.get("mismatchReasons")).isEqualTo(List.of());
        assertThat(replay.toString()).contains("VALUE_ABOVE_LIMIT");

        Map<?, ?> preview = post("/api/v1/capability-governance/blast-radius-preview", Map.of(
                "actionName", "ACCEPT_TRANSACTION",
                "policyName", "capability_policy",
                "policyVersion", "capability_policy_v1",
                "candidateParticipantIds", List.of(allowed.toString(), denied.toString()),
                "valueCents", 5000,
                "requestedBy", "operator@example.com",
                "reason", "Preview capability hardening"
        ), null).getBody();
        assertThat(preview.get("candidateCount")).isEqualTo(2);
        assertThat((Integer) preview.get("deniedCount")).isGreaterThanOrEqualTo(1);
        assertThat(countRows("select count(*) from capability_decision_logs where participant_id in (?, ?)", allowed, denied))
                .isEqualTo(1);

        Map<?, ?> bundle = get("/api/v1/capability-governance/audit-bundle/participants/" + denied).getBody();
        assertThat(bundle.toString()).contains(denied.toString(), decisionId.toString(), "participant_capability_governance");
        assertThat(bundle.toString()).doesNotContain(allowed.toString());
    }
}
