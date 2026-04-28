package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListingPublishSearchModerationIntegrationTest extends MarketplaceLifecycleIntegrationTestSupport {

    @Test
    void publishSearchAndModerationVisibilityRulesWork() {
        UUID provider = createCapableParticipant("publish-provider", "Publish Provider", "OFFER_SERVICES");
        UUID seller = createCapableParticipant("publish-seller", "Publish Seller", "SELL_ITEMS");
        UUID restricted = createCapableParticipant("publish-restricted", "Publish Restricted", "OFFER_SERVICES");
        UUID blockedListing = createListing(restricted, "SERVICE_OFFER", "TUTORING", "Blocked publish", 2500L, null, serviceDetails());
        post("/api/v1/participants/" + restricted + "/account-status", Map.of(
                "newStatus", "SUSPENDED", "actor", "operator@example.com", "reason", "Safety hold"
        ), "suspend-publish-restricted");

        UUID service = createListing(provider, "SERVICE_OFFER", "TUTORING", "Live tutoring publish", 2500L, null, serviceDetails());
        assertThat(publish(service).get("status")).isEqualTo("LIVE");

        UUID item = createListing(seller, "ITEM_LISTING", "ELECTRONICS", "High risk laptop publish", 90000L, null, itemDetails(true));
        Map<?, ?> itemPublish = publish(item);
        assertThat(itemPublish.get("status")).isIn("UNDER_REVIEW", "HIDDEN");
        assertThat(getList("/api/v1/listings/" + item + "/evidence-requirements").getBody()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(countRows("select count(*) from listing_evidence_requirements where listing_id = ?", item)).isGreaterThanOrEqualTo(2);

        assertThat(post("/api/v1/listings/" + blockedListing + "/publish", Map.of("actor", "p", "reason", "try"), "blocked-publish")
                .getStatusCode().value()).isEqualTo(403);

        assertThat(list(get("/api/v1/listings/search?query=Live").getBody(), "listings")).hasSize(1);
        post("/api/v1/listings/" + service + "/moderation/hide", Map.of("actor", "moderator@example.com", "reason", "Review"), "hide-live");
        assertThat(list(get("/api/v1/listings/search?query=Live").getBody(), "listings")).isEmpty();
        post("/api/v1/listings/" + service + "/moderation/restore", Map.of("actor", "moderator@example.com", "reason", "Restore"), "restore-live");
        assertThat(list(get("/api/v1/listings/search?query=Live").getBody(), "listings")).hasSize(1);
        post("/api/v1/listings/" + service + "/moderation/request-evidence", Map.of("actor", "moderator@example.com", "reason", "Need placeholder"), "evidence-live");
        assertThat(list(get("/api/v1/listings/search?query=Live").getBody(), "listings")).isEmpty();
        post("/api/v1/listings/" + service + "/moderation/reject", Map.of("actor", "moderator@example.com", "reason", "Reject"), "reject-live");
        post("/api/v1/listings/" + service + "/moderation/expire", Map.of("actor", "moderator@example.com", "reason", "Expire"), "expire-live");
        assertThat(get("/api/v1/listings/" + service).getBody().get("status")).isEqualTo("EXPIRED");
    }
}
