package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Pr7CarryForwardHardeningIntegrationTest extends Tg181To220IntegrationTestSupport {

    @Test
    void policyExceptionsAreScopedSimulationUsesEvaluatorAndTransitionsValidateRowsAndInputs() {
        UUID seller = createCapableParticipant("pr7-seller-" + suffix(), "PR7 Seller", "SELL_ITEMS");
        UUID listing = createListing(seller, "ITEM_LISTING", "ELECTRONICS", "PR7 high value " + suffix(),
                250000L, null, itemDetails(true));
        publish(listing);

        createRiskPolicyWithRule("risk_policy_pr7_" + suffix(), "high_value_block", "RISK_RULE", "LISTING",
                condition("valueCents", "greater_than", 100000), "BLOCK_TRANSACTION");
        String version = jdbcTemplate.queryForObject("""
                select policy_version from trust_policy_versions where policy_name = 'risk_policy' order by created_at desc limit 1
                """, String.class);

        requestAndApproveException("risk_policy", version, "LISTING", listing, "BYPASS_EXTRA_EVIDENCE");
        Map<?, ?> blocked = evaluatePolicy("risk_policy", version, "LISTING", listing, Map.of());
        assertThat(blocked.get("decision")).isEqualTo("BLOCK_TRANSACTION");
        assertThat(blocked.toString()).contains("ignoredExceptions");

        requestAndApproveException("risk_policy", version, "LISTING", listing, "ALLOW_HIGH_VALUE");
        Map<?, ?> softened = evaluatePolicy("risk_policy", version, "LISTING", listing, Map.of());
        assertThat(softened.get("decision")).isEqualTo("ALLOW_WITH_LIMITS");

        var invalidAction = post("/api/v1/policy-engine/rules", Map.of(
                "policyName", "risk_policy",
                "policyVersion", version,
                "ruleKey", "bad_action",
                "ruleType", "RISK_RULE",
                "targetScope", "LISTING",
                "condition", condition("valueCents", "greater_than", 1),
                "action", decision("RUN_ARBITRARY_THING")
        ), null);
        assertThat(invalidAction.getStatusCode().value()).isEqualTo(400);

        var invalidNumber = post("/api/v1/policy-engine/rules", Map.of(
                "policyName", "risk_policy",
                "policyVersion", version,
                "ruleKey", "bad_number",
                "ruleType", "RISK_RULE",
                "targetScope", "LISTING",
                "condition", condition("valueCents", "greater_than", "not-a-number"),
                "action", decision("REQUIRE_MANUAL_REVIEW")
        ), null);
        assertThat(invalidNumber.getStatusCode().value()).isEqualTo(400);

        UUID policyId = createPolicy("risk_policy", "transition_pr7_" + suffix(), true);
        post("/api/v1/policies/" + policyId + "/request-approval",
                Map.of("requestedBy", "operator@example.com", "reason", "Transition proof"), null);
        post("/api/v1/policies/" + policyId + "/approve", Map.of(
                "approvedBy", "risk-lead@example.com",
                "reason", "Approve once",
                "riskAcknowledgement", "Scoped approval"), null);
        var doubleApprove = post("/api/v1/policies/" + policyId + "/approve", Map.of(
                "approvedBy", "risk-lead@example.com",
                "reason", "Approve twice",
                "riskAcknowledgement", "Scoped approval"), null);
        assertThat(doubleApprove.getStatusCode().value()).isEqualTo(409);

        var simulation = post("/api/v1/policy-simulations/shadow-risk", Map.of(
                "policyName", "risk_policy",
                "fromPolicyVersion", version,
                "toPolicyVersion", version,
                "requestedBy", "operator@example.com",
                "reason", "Evaluator backed simulation"), null);
        assertThat(simulation.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(simulation.getBody().toString()).contains("evaluatorBacked", "decisionDeltas");
        assertThat(simulation.getBody().toString()).doesNotContain("ML", "model inference");
    }
}
