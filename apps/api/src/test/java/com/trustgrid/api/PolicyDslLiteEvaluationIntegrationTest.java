package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PolicyDslLiteEvaluationIntegrationTest extends Tg161To180IntegrationTestSupport {

    @Test
    void dslLiteEvaluatesStructuredConditionsPriorityAndExplanations() {
        UUID seller = createCapableParticipant("dsl-seller-" + suffix(), "DSL Seller", "SELL_ITEMS");
        UUID listing = createListing(seller, "ITEM_LISTING", "ELECTRONICS", "dsl high value " + suffix(), 150000L, null, itemDetails(true));
        UUID policy = createPolicy("risk_policy", "risk_dsl_v1", true);
        addRule("risk_policy", "risk_dsl_v1", "electronics-high-value-review", "RISK_RULE", "LISTING",
                Map.of("conditions", List.of(
                        Map.of("field", "categoryCode", "operator", "equals", "value", "ELECTRONICS"),
                        Map.of("field", "valueCents", "operator", "greater_than_or_equal", "value", 100000)
                )), decision("REQUIRE_VERIFICATION"), 5);
        addRule("risk_policy", "risk_dsl_v1", "new-user-high-value-block", "RISK_RULE", "LISTING",
                condition("trustTier", "equals", "NEW"), decision("BLOCK_TRANSACTION"), 50);
        post("/api/v1/policy-engine/rules", Map.of(
                "policyName", "risk_policy", "policyVersion", "risk_dsl_v1",
                "ruleKey", "disabled-suspension", "ruleType", "RISK_RULE", "targetScope", "LISTING",
                "condition", condition("valueCents", "greater_than", 1),
                "action", decision("SUSPEND_ACCOUNT"), "priority", 1, "enabled", false), null);
        approveAndActivate(policy);

        Map<?, ?> response = evaluatePolicy("risk_policy", "risk_dsl_v1", "LISTING", listing,
                Map.of("categoryCode", "ELECTRONICS", "valueCents", 150000, "trustTier", "NEW"));
        assertThat(response.get("decision")).isEqualTo("REQUIRE_VERIFICATION");
        assertThat(response.toString()).contains("electronics-high-value-review", "new-user-high-value-block",
                "matchedConditions", "deterministic_rules_v1");
        assertThat(response.toString()).doesNotContain("disabled-suspension", "SUSPEND_ACCOUNT");
    }
}
