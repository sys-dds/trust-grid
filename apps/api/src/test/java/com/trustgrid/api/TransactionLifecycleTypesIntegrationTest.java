package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionLifecycleTypesIntegrationTest extends MarketplaceLifecycleIntegrationTestSupport {

    @Test
    void fourTransactionLifecycleHappyPathsRejectInvalidJumps() {
        UUID buyer = createCapableParticipant("lifecycle-buyer", "Lifecycle Buyer", "BUY");
        UUID provider = createCapableParticipant("lifecycle-provider", "Lifecycle Provider", "OFFER_SERVICES");
        UUID seller = createCapableParticipant("lifecycle-seller", "Lifecycle Seller", "SELL_ITEMS");
        UUID requester = createCapableParticipant("lifecycle-requester", "Lifecycle Requester", "BUY");
        UUID runner = createCapableParticipant("lifecycle-runner", "Lifecycle Runner", "ACCEPT_ERRANDS", "ACCEPT_SHOPPING_REQUESTS");

        UUID service = createListing(provider, "SERVICE_OFFER", "TUTORING", "Lifecycle service", 2500L, null, serviceDetails());
        UUID item = createListing(seller, "ITEM_LISTING", "CLOTHING", "Lifecycle item", 3000L, null, itemDetails(false));
        UUID errand = createListing(requester, "ERRAND_REQUEST", "ERRANDS", "Lifecycle errand", null, 1500L, errandDetails());
        UUID shopping = createListing(requester, "SHOPPING_REQUEST", "SHOPPING", "Lifecycle shopping", null, null, shoppingDetails());
        publish(service);
        publish(item);
        publish(errand);
        publish(shopping);

        UUID serviceTx = createTransaction(service, buyer, provider, "service-tx");
        post("/api/v1/transactions/" + serviceTx + "/start", action(provider), "service-start");
        post("/api/v1/transactions/" + serviceTx + "/claim-completion", action(provider), "service-claim");
        assertThat(post("/api/v1/transactions/" + serviceTx + "/mark-shipped", action(provider), "bad-ship").getStatusCode().value()).isEqualTo(409);
        post("/api/v1/transactions/" + serviceTx + "/confirm-completion", action(buyer), "service-confirm");
        assertThat(get("/api/v1/transactions/" + serviceTx).getBody().get("status")).isEqualTo("COMPLETED");

        UUID itemTx = createTransaction(item, buyer, seller, "item-tx");
        post("/api/v1/transactions/" + itemTx + "/mark-shipped", action(seller), "item-ship");
        post("/api/v1/transactions/" + itemTx + "/mark-delivered", action(seller), "item-deliver");
        post("/api/v1/transactions/" + itemTx + "/confirm-completion", action(buyer), "item-confirm");

        UUID errandTx = createTransaction(errand, requester, runner, "errand-tx");
        post("/api/v1/transactions/" + errandTx + "/start", action(runner), "errand-start");
        post("/api/v1/transactions/" + errandTx + "/mark-proof-placeholder", action(runner), "errand-proof");
        post("/api/v1/transactions/" + errandTx + "/confirm-completion", action(requester), "errand-confirm");

        UUID shoppingTx = createTransaction(shopping, requester, runner, "shopping-tx");
        post("/api/v1/transactions/" + shoppingTx + "/mark-purchase-proof-placeholder", action(runner), "shop-proof-1");
        post("/api/v1/transactions/" + shoppingTx + "/mark-delivery-proof-placeholder", action(runner), "shop-proof-2");
        post("/api/v1/transactions/" + shoppingTx + "/confirm-completion", action(requester), "shop-confirm");

        assertThat(countRows("select count(*) from marketplace_events where event_type = 'TRANSACTION_COMPLETED'")).isGreaterThanOrEqualTo(4);
    }

    Map<String, Object> action(UUID participantId) {
        return Map.of("actorParticipantId", participantId.toString(), "actor", "participant@example.com", "reason", "Lifecycle action");
    }
}
