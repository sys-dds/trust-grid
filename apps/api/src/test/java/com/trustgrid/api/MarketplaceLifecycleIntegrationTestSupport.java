package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

abstract class MarketplaceLifecycleIntegrationTestSupport extends ParticipantIntegrationTestSupport {

    UUID createCapableParticipant(String slug, String name, String... capabilities) {
        UUID participantId = participantId(createParticipant(slug, name, "create-" + slug));
        for (String capability : capabilities) {
            ResponseEntity<Map> response = post("/api/v1/participants/" + participantId + "/capabilities", Map.of(
                    "capability", capability,
                    "actor", "operator@example.com",
                    "reason", "Grant test capability"
            ), "cap-" + participantId + "-" + capability);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        }
        return participantId;
    }

    UUID createListing(UUID ownerId, String type, String category, String title, Long price, Long budget, Map<String, Object> details) {
        Map<String, Object> body = new HashMap<>();
        body.put("ownerParticipantId", ownerId.toString());
        body.put("listingType", type);
        body.put("categoryCode", category);
        body.put("title", title);
        body.put("description", "Useful marketplace listing for integration proof");
        body.put("priceAmountCents", price);
        body.put("budgetAmountCents", budget);
        body.put("currency", "GBP");
        body.put("locationMode", type.equals("ITEM_LISTING") ? "LOCAL_PICKUP" : "REMOTE");
        body.put("singleAccept", true);
        body.put("createdBy", "participant@example.com");
        body.put("reason", "Create listing draft");
        body.put("details", details);
        ResponseEntity<Map> response = post("/api/v1/listings", body, "listing-" + title.replace(" ", "-") + "-" + UUID.randomUUID());
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString((String) response.getBody().get("listingId"));
    }

    Map<?, ?> publish(UUID listingId) {
        ResponseEntity<Map> response = post("/api/v1/listings/" + listingId + "/publish", Map.of(
                "actor", "participant@example.com",
                "reason", "Publish listing"
        ), "publish-" + listingId + "-" + UUID.randomUUID());
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody();
    }

    UUID createTransaction(UUID listingId, UUID requesterId, UUID providerId, String key) {
        ResponseEntity<Map> response = post("/api/v1/listings/" + listingId + "/transactions", Map.of(
                "requesterParticipantId", requesterId.toString(),
                "providerParticipantId", providerId.toString(),
                "actor", "participant@example.com",
                "reason", "Accept listing",
                "metadata", Map.of("proof", "integration")
        ), key);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString((String) response.getBody().get("transactionId"));
    }

    Map<String, Object> serviceDetails() {
        return Map.of(
                "pricingModel", "HOURLY",
                "remoteAllowed", true,
                "inPersonAllowed", false,
                "serviceDurationMinutes", 60,
                "trialAllowed", false,
                "cancellationPolicy", "24 hours notice",
                "availabilitySummary", "Weekends"
        );
    }

    Map<String, Object> itemDetails(boolean highValue) {
        return Map.of(
                "itemCondition", "GOOD",
                "brand", "TrustGrid",
                "highValue", highValue,
                "shippingAllowed", true,
                "localPickupAllowed", true
        );
    }

    Map<String, Object> errandDetails() {
        return Map.of(
                "pickupSummary", "City centre",
                "dropoffSummary", "West End",
                "deadlineAt", "2026-05-01T16:00:00Z",
                "proofRequired", true,
                "localOnly", true,
                "safetyCategory", "LOCAL_DELIVERY"
        );
    }

    Map<String, Object> shoppingDetails() {
        return Map.of(
                "targetItemDescription", "Buy a jacket from a local shop",
                "targetShopSource", "Local shop",
                "buyerBudgetCents", 9000,
                "shopperRewardCents", 1000,
                "receiptRequired", true,
                "deliveryProofRequired", true
        );
    }

    ResponseEntity<java.util.List> getList(String path) {
        return restTemplate.getForEntity(url(path), java.util.List.class);
    }
}
