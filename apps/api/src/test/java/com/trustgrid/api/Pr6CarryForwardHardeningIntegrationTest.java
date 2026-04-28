package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class Pr6CarryForwardHardeningIntegrationTest extends Tg161To180IntegrationTestSupport {

    @Test
    void paymentBoundaryModeratorAppealReviewAbuseAndSearchRebuildAreHardened() {
        Flow completed = createCompletedServiceFlow("pbh-release");
        post("/api/v1/transactions/" + completed.transactionId() + "/payment-boundary/request-release", actorReason(), null);
        post("/api/v1/transactions/" + completed.transactionId() + "/payment-boundary/request-release", actorReason(), null);
        assertThat(countRows("""
                select count(*) from payment_boundary_events
                where transaction_id = ? and event_type = 'MARKETPLACE_FUNDS_RELEASE_REQUESTED'
                """, completed.transactionId())).isEqualTo(1);

        Flow lowRisk = createCompletedServiceFlow("pbh-hold-low");
        ResponseEntity<Map> lowRiskHold = post("/api/v1/transactions/" + lowRisk.transactionId()
                + "/payment-boundary/request-payout-hold", actorReason(), null);
        assertThat(lowRiskHold.getStatusCode().value()).isEqualTo(409);
        jdbcTemplate.update("""
                insert into risk_decisions (id, target_type, target_id, score, risk_level, decision, policy_version)
                values (?, 'PARTICIPANT', ?, 90, 'HIGH', 'REQUIRE_MANUAL_REVIEW', 'risk_rules_v1')
                """, UUID.randomUUID(), lowRisk.providerId());
        post("/api/v1/transactions/" + lowRisk.transactionId() + "/payment-boundary/request-payout-hold", actorReason(), null);
        post("/api/v1/transactions/" + lowRisk.transactionId() + "/payment-boundary/close", actorReason(), null);

        Flow disputed = createDisputableServiceFlow("pbh-refund");
        UUID dispute = openDispute(disputed, "pbh-dispute-" + suffix());
        ResponseEntity<Map> unresolvedRefund = post("/api/v1/transactions/" + disputed.transactionId()
                + "/payment-boundary/request-refund", actorReason(), null);
        assertThat(unresolvedRefund.getStatusCode().value()).isEqualTo(409);
        post("/api/v1/disputes/" + dispute + "/resolve", Map.of(
                "outcome", "SAFETY_ESCALATION",
                "resolvedBy", "moderator@example.com",
                "reason", "Safety evidence requires refund review"), null);
        post("/api/v1/transactions/" + disputed.transactionId() + "/payment-boundary/request-refund", actorReason(), null);

        UUID participant = createCapableParticipant("moderator-hard-" + suffix(), "Moderator Hard", "SELL_ITEMS", "OFFER_SERVICES");
        post("/api/v1/ops/moderator-actions/restrict-capability", Map.of(
                "targetType", "PARTICIPANT", "targetId", participant.toString(), "participantId", participant.toString(),
                "capability", "SELL_ITEMS", "actor", "moderator@example.com", "reason", "Specific capability risk"), null);
        assertThat(capabilityStatus(participant, "SELL_ITEMS")).isEqualTo("RESTRICTED");
        post("/api/v1/ops/moderator-actions/restore-capability", Map.of(
                "targetType", "PARTICIPANT", "targetId", participant.toString(), "participantId", participant.toString(),
                "capability", "SELL_ITEMS", "actor", "moderator@example.com", "reason", "Specific capability restored"), null);
        assertThat(capabilityStatus(participant, "SELL_ITEMS")).isEqualTo("ACTIVE");
        assertThat(capabilityStatus(participant, "OFFER_SERVICES")).isEqualTo("ACTIVE");

        jdbcTemplate.update("update participant_capabilities set status = 'RESTRICTED' where participant_id = ?", participant);
        UUID sellCapabilityId = jdbcTemplate.queryForObject("""
                select id from participant_capabilities where participant_id = ? and capability = 'SELL_ITEMS'
                """, UUID.class, participant);
        Map<?, ?> appeal = post("/api/v1/participants/" + participant + "/appeals", Map.of(
                "targetType", "PARTICIPANT_CAPABILITY",
                "targetId", sellCapabilityId.toString(),
                "appealReason", "Restore one capability",
                "metadata", Map.of()), null).getBody();
        post("/api/v1/appeals/" + appeal.get("appealId") + "/decide", Map.of(
                "decision", "CAPABILITY_RESTORED",
                "decidedBy", "moderator@example.com",
                "reason", "Only selling capability restored"), null);
        assertThat(capabilityStatus(participant, "SELL_ITEMS")).isEqualTo("ACTIVE");
        assertThat(capabilityStatus(participant, "OFFER_SERVICES")).isEqualTo("RESTRICTED");

        UUID reviewed = createCapableParticipant("abuse-reviewed-" + suffix(), "Abuse Reviewed", "OFFER_SERVICES");
        UUID r1 = createCapableParticipant("abuse-r1-" + suffix(), "Abuse R1", "BUY");
        UUID r2 = createCapableParticipant("abuse-r2-" + suffix(), "Abuse R2", "BUY");
        UUID r3 = createCapableParticipant("abuse-r3-" + suffix(), "Abuse R3", "BUY");
        UUID cheapOne = cheapReview(r1, reviewed, "abuse-one");
        post("/api/v1/review-graph/rebuild", Map.of(), null);
        assertThat(countRows("select count(*) from review_abuse_clusters where cluster_type = 'LOW_VALUE_REVIEW_FARMING'")).isZero();
        cheapReview(r2, reviewed, "abuse-two");
        cheapReview(r3, reviewed, "abuse-three");
        post("/api/v1/review-graph/rebuild", Map.of(), null);
        assertThat(countRows("select count(*) from review_abuse_clusters where cluster_type = 'LOW_VALUE_REVIEW_FARMING'")).isPositive();
        assertThat(jdbcTemplate.queryForObject("select signals_json::text from review_abuse_clusters where cluster_type = 'LOW_VALUE_REVIEW_FARMING' order by created_at desc limit 1",
                String.class)).contains("rule_key", "threshold", "observed_count", cheapOne.toString());

        UUID listing = createListing(reviewed, "SERVICE_OFFER", "TUTORING", "search rebuild hard " + suffix(), 2500L, null, serviceDetails());
        publish(listing);
        jdbcTemplate.update("""
                insert into participant_restrictions (id, participant_id, restriction_type, status, actor, reason)
                values (?, ?, 'LISTING_BLOCKED', 'ACTIVE', 'moderator@example.com', 'Search restriction')
                """, UUID.randomUUID(), reviewed);
        post("/api/v1/rebuilds/search-index", actorReason(), null);
        assertThat(get("/api/v1/listings/search?query=search%20rebuild%20hard").getBody().toString()).doesNotContain(listing.toString());
    }

    private String capabilityStatus(UUID participantId, String capability) {
        return jdbcTemplate.queryForObject("""
                select status from participant_capabilities where participant_id = ? and capability = ?
                """, String.class, participantId, capability);
    }

    private UUID cheapReview(UUID buyer, UUID provider, String prefix) {
        UUID listing = createListing(provider, "SERVICE_OFFER", "TUTORING", prefix + " cheap " + suffix(), 500L, null, serviceDetails());
        publish(listing);
        UUID transaction = createTransaction(listing, buyer, provider, prefix + "-tx-" + suffix());
        post("/api/v1/transactions/" + transaction + "/start", action(provider), prefix + "-start-" + suffix());
        post("/api/v1/transactions/" + transaction + "/claim-completion", action(provider), prefix + "-claim-" + suffix());
        post("/api/v1/transactions/" + transaction + "/confirm-completion", action(buyer), prefix + "-confirm-" + suffix());
        return review(transaction, buyer, provider, 5, "Helpful and quick", prefix + "-review-" + suffix());
    }
}
