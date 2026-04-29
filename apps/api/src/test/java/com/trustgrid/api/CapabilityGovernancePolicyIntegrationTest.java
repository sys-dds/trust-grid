package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapabilityGovernancePolicyIntegrationTest extends Tg221To240IntegrationTestSupport {

    @Test
    void capabilityPoliciesCoverMarketplaceActionsAndEvaluateDeterministicInputs() {
        for (String action : java.util.List.of("PUBLISH_LISTING", "ACCEPT_TRANSACTION", "OPEN_DISPUTE",
                "CREATE_REVIEW", "RECEIVE_SEARCH_EXPOSURE", "REQUEST_PAYMENT_RELEASE")) {
            createCapabilityPolicy(action, Map.of(
                    "minTrustTier", "NEW",
                    "requiredVerificationStatus", "UNVERIFIED",
                    "maxRiskLevel", "HIGH"
            ));
        }
        assertThat(getList("/api/v1/capability-governance/policies").getBody()).hasSizeGreaterThanOrEqualTo(6);

        UUID seller = createCapableParticipant("cap-policy-" + suffix(), "Capability Policy", "SELL_ITEMS");
        verifyParticipant(seller);
        UUID listing = createListing(seller, "ITEM_LISTING", "ELECTRONICS", "Capability item " + suffix(),
                5000L, null, itemDetails(false));
        publish(listing);
        Map<?, ?> allowed = simulateCapability(seller, "PUBLISH_LISTING", "LISTING", listing, 5000L);
        assertThat(allowed.get("decision")).isEqualTo("ALLOW");
        assertThat(allowed.toString()).contains("deterministicRulesVersion", "capability_governance_v1");

        restrict(seller, "LISTING_BLOCKED");
        Map<?, ?> denied = simulateCapability(seller, "PUBLISH_LISTING", "LISTING", listing, 5000L);
        assertThat(denied.get("decision")).isIn("DENY", "REQUIRE_MANUAL_REVIEW");
        assertThat(denied.toString()).contains("CAPABILITY_RESTRICTED", "resolve or appeal the exact active restriction");
        assertThat(countRows("select count(*) from capability_decision_logs where participant_id = ?", seller))
                .isGreaterThanOrEqualTo(2);

        assertThat(post("/api/v1/capability-governance/policies", Map.of(
                "actionName", "ACCEPT_TRANSACTION",
                "policyName", "capability_policy",
                "policyVersion", "missing_actor",
                "reason", "Missing creator"
        ), null).getStatusCode().value()).isEqualTo(400);

        assertThat(post("/api/v1/capability-governance/policies", Map.of(
                "actionName", "UNSUPPORTED_ACTION",
                "policyName", "capability_policy",
                "policyVersion", "unsupported",
                "createdBy", "operator@example.com",
                "reason", "Unsupported action"
        ), null).getStatusCode().value()).isEqualTo(400);
    }
}
