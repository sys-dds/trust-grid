package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class PolicyEngineLifecycleIntegrationTest extends Tg161To180IntegrationTestSupport {

    @Test
    void policyLifecycleRequiresApprovalActivatesOneVersionAndRestoresPreviousVersion() {
        UUID participant = createCapableParticipant("policy-life-" + suffix(), "Policy Life", "BUY");
        UUID v1 = createPolicy("risk_policy", "risk_lifecycle_v1", true);
        addRule("risk_policy", "risk_lifecycle_v1", "new-user-review", "RISK_RULE", "PARTICIPANT",
                condition("trustTier", "equals", "NEW"), decision("REQUIRE_MANUAL_REVIEW"), 10);
        approveAndActivate(v1);

        UUID v2 = createPolicy("risk_policy", "risk_lifecycle_v2", true);
        addRule("risk_policy", "risk_lifecycle_v2", "new-user-block", "RISK_RULE", "PARTICIPANT",
                condition("trustTier", "equals", "NEW"), decision("BLOCK_TRANSACTION"), 5);
        ResponseEntity<Map> blockedActivation = post("/api/v1/policies/" + v2 + "/activate", Map.of(), null);
        assertThat(blockedActivation.getStatusCode().value()).isEqualTo(409);
        approveAndActivate(v2);

        assertThat(getList("/api/v1/policies/active").getBody().toString()).contains("risk_lifecycle_v2").doesNotContain("risk_lifecycle_v1, status=ACTIVE");
        Map<?, ?> decision = evaluatePolicy("risk_policy", "risk_lifecycle_v2", "PARTICIPANT", participant, Map.of("trustTier", "NEW"));
        assertThat(decision.get("decision")).isEqualTo("BLOCK_TRANSACTION");
        assertThat(decision.toString()).contains("new-user-block", "deterministic_rules_v1");

        post("/api/v1/policies/" + v2 + "/rollback", Map.of(
                "actor", "risk-lead@example.com",
                "reason", "Restore previous deterministic policy",
                "riskAcknowledgement", "Prior active version is safer for this proof"), null);
        assertThat(getList("/api/v1/policies/active").getBody().toString()).contains("risk_lifecycle_v1");
        assertThat(countRows("select count(*) from marketplace_events where event_type in ('POLICY_APPROVAL_REQUESTED','POLICY_APPROVAL_RECORDED','POLICY_ACTIVATED','POLICY_ROLLBACK_COMPLETED')")).isGreaterThanOrEqualTo(4);
    }
}
