package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryAndListingFoundationIntegrationTest extends MarketplaceLifecycleIntegrationTestSupport {

    @Test
    void categoryCatalogAndFourDraftListingTypesWork() {
        assertThat(getList("/api/v1/categories").getBody()).hasSizeGreaterThanOrEqualTo(11);
        assertThat(get("/api/v1/categories/TUTORING").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(countRows("select count(*) from marketplace_categories")).isGreaterThanOrEqualTo(11);
        assertThat(countRows("select count(*) from marketplace_categories where default_risk_tier in ('LOW','MEDIUM','HIGH','RESTRICTED')")).isGreaterThanOrEqualTo(11);

        UUID serviceOwner = createCapableParticipant("service-foundation", "Service Foundation", "OFFER_SERVICES");
        UUID seller = createCapableParticipant("seller-foundation", "Seller Foundation", "SELL_ITEMS");
        UUID requester = createCapableParticipant("requester-foundation", "Requester Foundation", "BUY");

        UUID service = createListing(serviceOwner, "SERVICE_OFFER", "TUTORING", "Java tutoring foundation", 2500L, null, serviceDetails());
        UUID item = createListing(seller, "ITEM_LISTING", "CLOTHING", "Jacket foundation", 4000L, null, itemDetails(false));
        UUID errand = createListing(requester, "ERRAND_REQUEST", "ERRANDS", "Pickup foundation", null, 1500L, errandDetails());
        UUID shopping = createListing(requester, "SHOPPING_REQUEST", "SHOPPING", "Shopping foundation", null, null, shoppingDetails());

        assertThat(get("/api/v1/listings/" + service).getBody().get("status")).isEqualTo("DRAFT");
        assertThat(get("/api/v1/listings/" + item).getBody().get("status")).isEqualTo("DRAFT");
        assertThat(get("/api/v1/listings/" + errand).getBody().get("status")).isEqualTo("DRAFT");
        assertThat(get("/api/v1/listings/" + shopping).getBody().get("status")).isEqualTo("DRAFT");
        assertThat(countRows("select count(*) from marketplace_events where event_type = 'LISTING_CREATED'")).isGreaterThanOrEqualTo(4);
    }
}
