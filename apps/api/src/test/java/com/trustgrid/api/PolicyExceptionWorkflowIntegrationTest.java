package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PolicyExceptionWorkflowIntegrationTest extends Tg161To180IntegrationTestSupport {

    @Test
    void policyExceptionsAreScopedAuditedExpireAndCanBeRevoked() {
        UUID seller = createCapableParticipant("exception-seller-" + suffix(), "Exception Seller", "SELL_ITEMS");
        UUID listing = createListing(seller, "ITEM_LISTING", "ELECTRONICS", "exception high value " + suffix(), 160000L, null, itemDetails(true));
        UUID otherListing = createListing(seller, "ITEM_LISTING", "ELECTRONICS", "exception other high value " + suffix(), 160000L, null, itemDetails(true));
        UUID policy = createPolicy("risk_policy", "risk_exception_v1", true);
        addRule("risk_policy", "risk_exception_v1", "high-value-block", "RISK_RULE", "LISTING",
                condition("valueCents", "greater_than", 100000), decision("BLOCK_TRANSACTION"), 10);
        approveAndActivate(policy);

        assertThat(evaluatePolicy("risk_policy", "risk_exception_v1", "LISTING", listing,
                Map.of("valueCents", 160000)).get("decision")).isEqualTo("BLOCK_TRANSACTION");
        UUID exceptionId = requestAndApproveException("risk_policy", "risk_exception_v1", "LISTING", listing, "ALLOW_HIGH_VALUE");
        Map<?, ?> allowed = evaluatePolicy("risk_policy", "risk_exception_v1", "LISTING", listing,
                Map.of("valueCents", 160000));
        assertThat(allowed.get("decision")).isEqualTo("ALLOW_WITH_LIMITS");
        assertThat(allowed.toString()).contains(exceptionId.toString());
        assertThat(evaluatePolicy("risk_policy", "risk_exception_v1", "LISTING", otherListing,
                Map.of("valueCents", 160000)).get("decision")).isEqualTo("BLOCK_TRANSACTION");

        post("/api/v1/policy-exceptions/" + exceptionId + "/revoke", Map.of(
                "actor", "risk-lead@example.com",
                "reason", "Scoped exception no longer needed"), null);
        assertThat(evaluatePolicy("risk_policy", "risk_exception_v1", "LISTING", listing,
                Map.of("valueCents", 160000)).get("decision")).isEqualTo("BLOCK_TRANSACTION");

        Map<?, ?> expired = post("/api/v1/policy-exceptions", Map.of(
                "policyName", "risk_policy", "policyVersion", "risk_exception_v1",
                "targetType", "LISTING", "targetId", listing.toString(),
                "exceptionType", "ALLOW_HIGH_VALUE", "requestedBy", "operator@example.com",
                "reason", "Expired proof", "expiresAt", "2020-01-01T00:00:00Z"), null).getBody();
        post("/api/v1/policy-exceptions/" + expired.get("id") + "/approve", Map.of(
                "approvedBy", "risk-lead@example.com", "reason", "Approve expired proof",
                "riskAcknowledgement", "Expired exception should not apply"), null);
        assertThat(evaluatePolicy("risk_policy", "risk_exception_v1", "LISTING", listing,
                Map.of("valueCents", 160000)).get("decision")).isEqualTo("BLOCK_TRANSACTION");
        assertThat(countRows("select count(*) from marketplace_events where event_type like 'POLICY_EXCEPTION_%'")).isGreaterThanOrEqualTo(3);
    }
}
