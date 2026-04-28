package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PolicyExplainabilityAuditIntegrationTest extends Tg161To180IntegrationTestSupport {

    @Test
    void policyExplanationsExposeMatchedRulesPolicyVersionExceptionsAndAuditEvents() {
        UUID seller = createCapableParticipant("explain-seller-" + suffix(), "Explain Seller", "SELL_ITEMS");
        UUID listing = createListing(seller, "ITEM_LISTING", "ELECTRONICS", "explain high value " + suffix(), 190000L, null, itemDetails(true));
        UUID policy = createPolicy("risk_policy", "risk_explain_v1", true);
        addRule("risk_policy", "risk_explain_v1", "explain-high-value", "RISK_RULE", "LISTING",
                condition("valueCents", "greater_than", 100000), decision("BLOCK_TRANSACTION"), 10);
        approveAndActivate(policy);
        UUID exceptionId = requestAndApproveException("risk_policy", "risk_explain_v1", "LISTING", listing, "ALLOW_HIGH_VALUE");

        Map<?, ?> decision = evaluatePolicy("risk_policy", "risk_explain_v1", "LISTING", listing,
                Map.of("valueCents", 190000));
        UUID decisionId = UUID.fromString(decision.get("decisionId").toString());
        Map<?, ?> byId = get("/api/v1/policy-engine/decisions/" + decisionId + "/explanation").getBody();
        Map<?, ?> byTarget = get("/api/v1/policy-engine/explain?targetType=LISTING&targetId=" + listing
                + "&policyName=risk_policy&policyVersion=risk_explain_v1").getBody();

        assertThat(byId.toString()).contains("risk_explain_v1", "explain-high-value", exceptionId.toString(),
                "deterministic_rules_v1", "inputSnapshot");
        assertThat(byTarget.get("decisionId")).isEqualTo(decisionId.toString());
        assertThat(byId.toString()).doesNotContain("AI", "ML confidence", "model inference");
        assertThat(countRows("""
                select count(*) from marketplace_events
                where event_type in ('POLICY_APPROVAL_REQUESTED','POLICY_APPROVAL_RECORDED','POLICY_ACTIVATED',
                                     'POLICY_EXCEPTION_APPROVED','POLICY_DECISION_RECORDED')
                """)).isGreaterThanOrEqualTo(5);
    }
}
