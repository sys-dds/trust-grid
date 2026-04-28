package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListingDuplicateRiskOutboxIntegrationTest extends MarketplaceLifecycleIntegrationTestSupport {

    @Test
    void duplicateRiskAndOutboxFieldsAreRecorded() {
        UUID owner = createCapableParticipant("duplicate-owner", "Duplicate Owner", "SELL_ITEMS");
        UUID first = createListing(owner, "ITEM_LISTING", "ELECTRONICS", "Duplicate Laptop", 90000L, null, itemDetails(true));
        UUID second = createListing(owner, "ITEM_LISTING", "ELECTRONICS", "Duplicate Laptop", 90000L, null, itemDetails(true));

        publish(first);
        publish(second);

        assertThat(countRows("select count(*) from duplicate_listing_findings where listing_id = ?", second)).isGreaterThanOrEqualTo(1);
        assertThat(countRows("select count(*) from listing_risk_snapshots where listing_id = ?", second)).isGreaterThanOrEqualTo(1);
        assertThat(countRows("""
                select count(*) from marketplace_events
                where aggregate_type = 'LISTING' and aggregate_id = ? and event_status = 'PENDING'
                  and publish_attempts = 0 and published_at is null and event_key is not null
                """, second)).isGreaterThanOrEqualTo(1);

        Map<String, Object> body = Map.ofEntries(
                Map.entry("ownerParticipantId", owner.toString()),
                Map.entry("listingType", "ITEM_LISTING"),
                Map.entry("categoryCode", "CLOTHING"),
                Map.entry("title", "Idempotent jacket"),
                Map.entry("description", "Jacket"),
                Map.entry("priceAmountCents", 1000),
                Map.entry("currency", "GBP"),
                Map.entry("locationMode", "LOCAL_PICKUP"),
                Map.entry("createdBy", "owner@example.com"),
                Map.entry("reason", "Create"),
                Map.entry("details", itemDetails(false))
        );
        post("/api/v1/listings", body, "same-listing-key");
        assertThat(post("/api/v1/listings", Map.ofEntries(
                Map.entry("ownerParticipantId", owner.toString()),
                Map.entry("listingType", "ITEM_LISTING"),
                Map.entry("categoryCode", "CLOTHING"),
                Map.entry("title", "Changed jacket"),
                Map.entry("description", "Jacket"),
                Map.entry("priceAmountCents", 1000),
                Map.entry("currency", "GBP"),
                Map.entry("locationMode", "LOCAL_PICKUP"),
                Map.entry("createdBy", "owner@example.com"),
                Map.entry("reason", "Create"),
                Map.entry("details", itemDetails(false))
        ), "same-listing-key").getStatusCode().value()).isEqualTo(409);
    }
}
