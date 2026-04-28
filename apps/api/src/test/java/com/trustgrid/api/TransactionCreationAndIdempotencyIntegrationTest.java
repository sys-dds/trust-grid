package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionCreationAndIdempotencyIntegrationTest extends MarketplaceLifecycleIntegrationTestSupport {

    @Test
    void createTransactionIsIdempotentAndConflictSafe() {
        UUID buyer = createCapableParticipant("transaction-buyer", "Transaction Buyer", "BUY");
        UUID provider = createCapableParticipant("transaction-provider", "Transaction Provider", "OFFER_SERVICES");
        UUID listing = createListing(provider, "SERVICE_OFFER", "TUTORING", "Transaction service", 2500L, null, serviceDetails());
        publish(listing);

        Map<?, ?> first = post("/api/v1/listings/" + listing + "/transactions", Map.of(
                "requesterParticipantId", buyer.toString(),
                "providerParticipantId", provider.toString(),
                "actor", "buyer@example.com",
                "reason", "Book service",
                "metadata", Map.of("slot", "morning")
        ), "transaction-create-key").getBody();
        Map<?, ?> second = post("/api/v1/listings/" + listing + "/transactions", Map.of(
                "requesterParticipantId", buyer.toString(),
                "providerParticipantId", provider.toString(),
                "actor", "buyer@example.com",
                "reason", "Book service",
                "metadata", Map.of("slot", "morning")
        ), "transaction-create-key").getBody();

        assertThat(second.get("transactionId")).isEqualTo(first.get("transactionId"));
        assertThat(post("/api/v1/listings/" + listing + "/transactions", Map.of(
                "requesterParticipantId", buyer.toString(),
                "providerParticipantId", provider.toString(),
                "actor", "buyer@example.com",
                "reason", "Changed",
                "metadata", Map.of("slot", "evening")
        ), "transaction-create-key").getStatusCode().value()).isEqualTo(409);
        assertThat(countRows("select count(*) from marketplace_transactions where listing_id = ?", listing)).isEqualTo(1);
        assertThat(countRows("select count(*) from marketplace_events where event_type = 'TRANSACTION_CREATED'")).isGreaterThanOrEqualTo(1);
    }
}
