package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustGrid021To060MarketplaceLifecycleE2EIntegrationTest extends MarketplaceLifecycleIntegrationTestSupport {

    @Test
    void marketplaceLifecycleEndToEndProof() {
        UUID buyer = createCapableParticipant("e2e-buyer", "E2E Buyer", "BUY");
        UUID provider = createCapableParticipant("e2e-provider", "E2E Provider", "OFFER_SERVICES");
        UUID seller = createCapableParticipant("e2e-seller", "E2E Seller", "SELL_ITEMS");
        UUID requester = createCapableParticipant("e2e-requester", "E2E Requester", "BUY");
        UUID runner = createCapableParticipant("e2e-runner", "E2E Runner", "ACCEPT_ERRANDS");
        UUID shopper = createCapableParticipant("e2e-shopper", "E2E Shopper", "ACCEPT_SHOPPING_REQUESTS");
        UUID restricted = createCapableParticipant("e2e-restricted", "E2E Restricted", "OFFER_SERVICES");
        UUID blocked = createListing(restricted, "SERVICE_OFFER", "TUTORING", "E2E blocked", 2500L, null, serviceDetails());
        post("/api/v1/participants/" + restricted + "/restrictions", Map.of(
                "restrictionType", "LISTING_BLOCKED",
                "actor", "operator@example.com",
                "reason", "Blocked from listing"
        ), "e2e-listing-block");

        assertThat(getList("/api/v1/categories").getBody()).hasSizeGreaterThanOrEqualTo(11);
        UUID service = createListing(provider, "SERVICE_OFFER", "TUTORING", "E2E service", 2500L, null, serviceDetails());
        UUID item = createListing(seller, "ITEM_LISTING", "ELECTRONICS", "E2E laptop", 90000L, null, itemDetails(true));
        createListing(requester, "ERRAND_REQUEST", "ERRANDS", "E2E errand", null, 1500L, errandDetails());
        createListing(requester, "SHOPPING_REQUEST", "SHOPPING", "E2E shopping", null, null, shoppingDetails());

        assertThat(publish(service).get("status")).isEqualTo("LIVE");
        assertThat(publish(item).get("status")).isIn("UNDER_REVIEW", "HIDDEN");
        assertThat(post("/api/v1/listings/" + blocked + "/publish", Map.of("actor", "r", "reason", "try"), "e2e-blocked-publish").getStatusCode().value()).isEqualTo(403);

        assertThat(list(get("/api/v1/listings/search?query=E2E").getBody(), "listings")).hasSize(1);
        UUID duplicate = createListing(provider, "SERVICE_OFFER", "TUTORING", "E2E service", 2500L, null, serviceDetails());
        publish(duplicate);
        assertThat(countRows("select count(*) from duplicate_listing_findings where listing_id = ?", duplicate)).isGreaterThanOrEqualTo(1);

        post("/api/v1/listings/" + service + "/moderation/hide", Map.of("actor", "moderator@example.com", "reason", "Hide"), "e2e-hide");
        assertThat(list(get("/api/v1/listings/search?query=E2E").getBody(), "listings")).isEmpty();
        post("/api/v1/listings/" + service + "/moderation/restore", Map.of("actor", "moderator@example.com", "reason", "Restore"), "e2e-restore");
        assertThat(list(get("/api/v1/listings/search?query=E2E").getBody(), "listings")).hasSize(1);

        UUID tx = createTransaction(service, buyer, provider, "e2e-tx");
        post("/api/v1/transactions/" + tx + "/start", action(provider), "e2e-start");
        post("/api/v1/transactions/" + tx + "/claim-completion", action(provider), "e2e-claim");
        post("/api/v1/transactions/" + tx + "/confirm-completion", action(buyer), "e2e-confirm");
        assertThat(get("/api/v1/transactions/" + tx).getBody().get("status")).isEqualTo("COMPLETED");
        assertThat(list(get("/api/v1/transactions/" + tx + "/timeline").getBody(), "events")).isNotEmpty();
        assertThat(post("/api/v1/transactions/invariants/verify", Map.of("transactionId", tx.toString()), null).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(countRows("select count(*) from marketplace_events where aggregate_type in ('LISTING','TRANSACTION') and event_status = 'PENDING'")).isGreaterThan(0);
        assertThat(get("/actuator/health/readiness").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/api/v1/system/ping").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(runner).isNotNull();
        assertThat(shopper).isNotNull();
    }

    Map<String, Object> action(UUID participantId) {
        return Map.of("actorParticipantId", participantId.toString(), "actor", "participant@example.com", "reason", "E2E action");
    }
}
