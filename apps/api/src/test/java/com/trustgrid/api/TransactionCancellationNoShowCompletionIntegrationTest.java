package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionCancellationNoShowCompletionIntegrationTest extends MarketplaceLifecycleIntegrationTestSupport {

    @Test
    void cancellationNoShowCompletionAndTerminalRulesWork() {
        UUID buyer = createCapableParticipant("cancel-buyer", "Cancel Buyer", "BUY");
        UUID provider = createCapableParticipant("cancel-provider", "Cancel Provider", "OFFER_SERVICES");
        UUID service = createListing(provider, "SERVICE_OFFER", "TUTORING", "Cancel service", 2500L, null, serviceDetails());
        publish(service);

        UUID cancelTx = createTransaction(service, buyer, provider, "cancel-tx");
        post("/api/v1/transactions/" + cancelTx + "/cancel", action(buyer), "cancel-key");
        assertThat(get("/api/v1/transactions/" + cancelTx).getBody().get("status")).isEqualTo("CANCELLED");
        assertThat(post("/api/v1/transactions/" + cancelTx + "/confirm-completion", action(buyer), "cancel-confirm").getStatusCode().value()).isEqualTo(409);

        UUID service2 = createListing(provider, "SERVICE_OFFER", "TUTORING", "No show service", 2500L, null, serviceDetails());
        publish(service2);
        UUID noShowTx = createTransaction(service2, buyer, provider, "no-show-tx");
        post("/api/v1/transactions/" + noShowTx + "/report-no-show", Map.of(
                "reportedByParticipantId", buyer.toString(),
                "actor", "buyer@example.com",
                "reason", "Provider did not attend"
        ), "no-show-key");
        assertThat(get("/api/v1/transactions/" + noShowTx).getBody().get("status")).isEqualTo("NO_SHOW_REPORTED");

        UUID service3 = createListing(provider, "SERVICE_OFFER", "TUTORING", "Complete service", 2500L, null, serviceDetails());
        publish(service3);
        UUID doneTx = createTransaction(service3, buyer, provider, "done-tx");
        post("/api/v1/transactions/" + doneTx + "/start", action(provider), "done-start");
        post("/api/v1/transactions/" + doneTx + "/claim-completion", action(provider), "done-claim");
        post("/api/v1/transactions/" + doneTx + "/confirm-completion", action(buyer), "done-confirm");
        assertThat(get("/api/v1/transactions/" + doneTx).getBody().get("status")).isEqualTo("COMPLETED");
    }

    Map<String, Object> action(UUID participantId) {
        return Map.of("actorParticipantId", participantId.toString(), "actor", "participant@example.com", "reason", "Transaction action");
    }
}
