package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapabilityAccessRegressionIntegrationTest extends Tg221To240IntegrationTestSupport {

    @Test
    void restrictedSuspendedClosedAndUnrelatedGrantsCannotBypassAccessGovernance() {
        createCapabilityPolicy("PUBLISH_LISTING", Map.of());
        createCapabilityPolicy("ACCEPT_TRANSACTION", Map.of());
        createCapabilityPolicy("RECEIVE_SEARCH_EXPOSURE", Map.of());
        createCapabilityPolicy("REQUEST_PAYMENT_RELEASE", Map.of());

        UUID seller = createCapableParticipant("access-seller-" + suffix(), "Access Seller", "SELL_ITEMS");
        UUID listing = createListing(seller, "ITEM_LISTING", "ELECTRONICS", "Access item " + suffix(),
                20000L, null, itemDetails(false));
        publish(listing);
        updateStatus(seller, "SUSPENDED");
        assertThat(simulateCapability(seller, "PUBLISH_LISTING", "LISTING", listing, 20000L).toString())
                .contains("ACCOUNT_SUSPENDED");

        UUID closed = createCapableParticipant("access-closed-" + suffix(), "Access Closed", "OFFER_SERVICES");
        updateStatus(closed, "CLOSED");
        assertThat(simulateCapability(closed, "ACCEPT_TRANSACTION", null, null, 1000L).toString())
                .contains("ACCOUNT_CLOSED");

        UUID restricted = createCapableParticipant("access-restricted-" + suffix(), "Access Restricted", "OFFER_SERVICES");
        restrict(restricted, "ACCEPTING_BLOCKED");
        assertThat(simulateCapability(restricted, "ACCEPT_TRANSACTION", null, null, 1000L).toString())
                .contains("CAPABILITY_RESTRICTED");

        UUID hidden = createCapableParticipant("access-hidden-" + suffix(), "Access Hidden", "SELL_ITEMS");
        UUID hiddenListing = createListing(hidden, "ITEM_LISTING", "ELECTRONICS", "Hidden access " + suffix(),
                1000L, null, itemDetails(false));
        publish(hiddenListing);
        restrict(hidden, "HIDDEN_FROM_MARKETPLACE_SEARCH");
        assertThat(simulateCapability(hidden, "RECEIVE_SEARCH_EXPOSURE", "LISTING", hiddenListing, 1000L).toString())
                .contains("SEARCH_VISIBILITY_SUPPRESSED");

        UUID unverified = createCapableParticipant("access-unverified-" + suffix(), "Access Unverified", "SELL_ITEMS");
        createCapabilityPolicy("PUBLISH_LISTING", Map.of("policyVersion", "capability_policy_v2",
                "requiredVerificationStatus", "VERIFIED"));
        var missingVerification = post("/api/v1/capability-governance/simulate", Map.of(
                "participantId", unverified.toString(),
                "actionName", "PUBLISH_LISTING",
                "policyName", "capability_policy",
                "policyVersion", "capability_policy_v2",
                "valueCents", 50000
        ), null);
        assertThat(missingVerification.getBody().toString()).contains("VERIFICATION_REQUIRED");

        temporaryGrant(restricted, "PUBLISH_LISTING", null, null, future());
        assertThat(simulateCapability(restricted, "ACCEPT_TRANSACTION", null, null, 1000L).toString())
                .contains("CAPABILITY_RESTRICTED");
        breakGlass(restricted, "ACCEPT_TRANSACTION", null, null, future());
        assertThat(simulateCapability(restricted, "ACCEPT_TRANSACTION", null, null, 1000L).get("decision"))
                .isEqualTo("ALLOW_WITH_BREAK_GLASS");

        Flow open = createDisputableServiceFlow("access-release");
        assertThat(simulateCapability(open.providerId(), "REQUEST_PAYMENT_RELEASE", "TRANSACTION",
                open.transactionId(), 2500L).toString()).contains("TRANSACTION_NOT_COMPLETED");
        Flow disputed = createCompletedServiceFlow("access-disputed-release");
        openDispute(disputed, "access-release-dispute-" + suffix());
        assertThat(simulateCapability(disputed.providerId(), "REQUEST_PAYMENT_RELEASE", "TRANSACTION",
                disputed.transactionId(), 2500L).toString()).contains("DISPUTE_UNRESOLVED");
    }
}
