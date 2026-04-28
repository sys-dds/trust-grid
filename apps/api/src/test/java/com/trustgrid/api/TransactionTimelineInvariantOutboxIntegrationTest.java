package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionTimelineInvariantOutboxIntegrationTest extends MarketplaceLifecycleIntegrationTestSupport {

    @Test
    void timelineInvariantsAndOutboxAreProven() {
        UUID buyer = createCapableParticipant("timeline-buyer", "Timeline Buyer", "BUY");
        UUID provider = createCapableParticipant("timeline-provider", "Timeline Provider", "OFFER_SERVICES");
        UUID listing = createListing(provider, "SERVICE_OFFER", "TUTORING", "Timeline service", 2500L, null, serviceDetails());
        publish(listing);
        UUID tx = createTransaction(listing, buyer, provider, "timeline-tx");
        post("/api/v1/transactions/" + tx + "/start", action(provider), "timeline-start");
        post("/api/v1/transactions/" + tx + "/claim-completion", action(provider), "timeline-claim");
        post("/api/v1/transactions/" + tx + "/confirm-completion", action(buyer), "timeline-confirm");

        Map<?, ?> timeline = get("/api/v1/transactions/" + tx + "/timeline").getBody();
        assertThat(list(timeline, "events")).isNotEmpty();
        Map<?, ?> invariants = post("/api/v1/transactions/invariants/verify", Map.of("transactionId", tx.toString()), null).getBody();
        assertThat(list(invariants, "results")).allSatisfy(result -> assertThat(((Map<?, ?>) result).get("status")).isEqualTo("PASS"));
        assertThat(countRows("""
                select count(*) from marketplace_events
                where aggregate_type = 'TRANSACTION' and aggregate_id = ? and event_status = 'PENDING'
                  and publish_attempts = 0 and published_at is null and event_key is not null
                """, tx)).isGreaterThanOrEqualTo(1);
    }

    Map<String, Object> action(UUID participantId) {
        return Map.of("actorParticipantId", participantId.toString(), "actor", "participant@example.com", "reason", "Timeline action");
    }
}
