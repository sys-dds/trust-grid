package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionRoleAuthorizationCarryForwardIntegrationTest extends EvidenceDisputeRiskIntegrationTestSupport {

    @Test
    void transactionMutationsRequireCorrectActorRoleAndDoNotPolluteAuditOnForbidden() {
        UUID buyer = createCapableParticipant("role-buyer-" + suffix(), "Role Buyer", "BUY");
        UUID provider = createCapableParticipant("role-provider-" + suffix(), "Role Provider", "OFFER_SERVICES");
        UUID listing = createListing(provider, "SERVICE_OFFER", "TUTORING", "Role auth service " + suffix(), 2500L, null, serviceDetails());
        publish(listing);
        UUID transactionId = createTransaction(listing, buyer, provider, "role-auth-tx-" + suffix());
        UUID unrelated = createCapableParticipant("role-unrelated-" + suffix(), "Unrelated", "BUY");

        int timelineBefore = countRows("select count(*) from transaction_timeline_events where transaction_id = ?", transactionId);
        int outboxBefore = countRows("select count(*) from marketplace_events where aggregate_id = ?", transactionId);

        assertThat(post("/api/v1/transactions/" + transactionId + "/start",
                action(buyer), "role-bad-start").getStatusCode().value()).isEqualTo(403);
        assertThat(post("/api/v1/transactions/" + transactionId + "/confirm-completion",
                action(provider), "role-bad-confirm").getStatusCode().value()).isEqualTo(403);
        assertThat(post("/api/v1/transactions/" + transactionId + "/cancel",
                action(unrelated), "role-bad-cancel").getStatusCode().value()).isEqualTo(403);
        assertThat(post("/api/v1/transactions/" + transactionId + "/report-no-show", Map.of(
                "reportedByParticipantId", unrelated.toString(),
                "actor", "unrelated@example.com",
                "reason", "Invalid no-show"
        ), "role-bad-no-show").getStatusCode().value()).isEqualTo(403);

        assertThat(countRows("select count(*) from transaction_timeline_events where transaction_id = ?", transactionId)).isEqualTo(timelineBefore);
        assertThat(countRows("select count(*) from marketplace_events where aggregate_id = ?", transactionId)).isEqualTo(outboxBefore);
        assertThat(countRows("select count(*) from idempotency_records where idempotency_key = 'role-bad-start'")).isZero();

        assertThat(post("/api/v1/transactions/" + transactionId + "/start",
                action(provider), "role-bad-start").getStatusCode().is2xxSuccessful()).isTrue();
    }
}
