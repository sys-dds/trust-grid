package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TransactionConcurrencyAndRiskGateIntegrationTest extends MarketplaceLifecycleIntegrationTestSupport {

    @Test
    void singleAcceptAndRiskGatesAreEnforced() throws Exception {
        UUID requester = createCapableParticipant("concurrency-requester", "Concurrency Requester", "BUY");
        UUID runner = createCapableParticipant("concurrency-runner", "Concurrency Runner", "ACCEPT_ERRANDS");
        UUID listing = createListing(requester, "ERRAND_REQUEST", "ERRANDS", "Concurrent errand", null, 1500L, errandDetails());
        publish(listing);

        AtomicInteger winners = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(10);
        try (var executor = Executors.newFixedThreadPool(10)) {
            for (int i = 0; i < 10; i++) {
                int attempt = i;
                executor.submit(() -> {
                    UUID candidate = attempt == 0 ? runner : createCapableParticipant("runner-" + attempt, "Runner " + attempt, "ACCEPT_ERRANDS");
                    if (post("/api/v1/listings/" + listing + "/transactions", Map.of(
                            "requesterParticipantId", requester.toString(),
                            "providerParticipantId", candidate.toString(),
                            "actor", "runner@example.com",
                            "reason", "Accept errand",
                            "metadata", Map.of("attempt", attempt)
                    ), "concurrent-" + attempt).getStatusCode().is2xxSuccessful()) {
                        winners.incrementAndGet();
                    }
                    latch.countDown();
                });
            }
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(winners.get()).isEqualTo(1);

        UUID limited = createCapableParticipant("limited-runner", "Limited Runner", "ACCEPT_ERRANDS");
        post("/api/v1/participants/" + limited + "/restrictions", Map.of(
                "restrictionType", "MAX_TRANSACTION_VALUE",
                "maxTransactionValueCents", 1,
                "actor", "operator@example.com",
                "reason", "Limit"
        ), "limit-runner");
        UUID secondListing = createListing(requester, "ERRAND_REQUEST", "ERRANDS", "Limited errand", null, 1500L, errandDetails());
        publish(secondListing);
        assertThat(post("/api/v1/listings/" + secondListing + "/transactions", Map.of(
                "requesterParticipantId", requester.toString(),
                "providerParticipantId", limited.toString(),
                "actor", "runner@example.com",
                "reason", "Accept errand",
                "metadata", Map.of()
        ), "limited-accept").getStatusCode().value()).isEqualTo(403);
    }
}
