package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DisputeLifecycleEvidenceBundleIntegrationTest extends EvidenceDisputeRiskIntegrationTestSupport {

    @Test
    void disputeLifecycleStatementsDeadlinesAndEvidenceBundleWork() {
        Flow flow = createDisputableServiceFlow("dispute-life");
        UUID earlyBuyer = createCapableParticipant("dispute-early-buyer-" + suffix(), "Early Buyer", "BUY");
        UUID earlyProvider = createCapableParticipant("dispute-early-provider-" + suffix(), "Early Provider", "OFFER_SERVICES");
        UUID earlyListing = createListing(earlyProvider, "SERVICE_OFFER", "TUTORING", "Early dispute service " + suffix(), 2500L, null, serviceDetails());
        publish(earlyListing);
        UUID earlyTx = createTransaction(earlyListing, earlyBuyer, earlyProvider, "early-dispute-tx-" + suffix());
        assertThat(post("/api/v1/transactions/" + earlyTx + "/disputes", Map.of(
                "openedByParticipantId", earlyBuyer.toString(),
                "disputeType", "SERVICE_NOT_DELIVERED",
                "reason", "Too early",
                "metadata", Map.of()
        ), "early-dispute-" + suffix()).getStatusCode().value()).isEqualTo(409);

        UUID disputeId = openDispute(flow, "dispute-life-open-" + suffix());
        assertThat(post("/api/v1/transactions/" + flow.transactionId() + "/disputes", Map.of(
                "openedByParticipantId", flow.buyerId().toString(),
                "disputeType", "SERVICE_NOT_DELIVERED",
                "reason", "Duplicate open",
                "metadata", Map.of()
        ), "duplicate-dispute-" + suffix()).getStatusCode().value()).isEqualTo(409);

        assertThat(post("/api/v1/disputes/" + disputeId + "/statements", Map.of(
                "participantId", flow.buyerId().toString(),
                "statementType", "BUYER_STATEMENT",
                "statementText", "The service was not complete.",
                "actor", "buyer@example.com",
                "reason", "Buyer statement"
        ), null).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(post("/api/v1/disputes/" + disputeId + "/status", Map.of(
                "newStatus", "UNDER_REVIEW",
                "actor", "moderator@example.com",
                "reason", "Evidence submitted"
        ), null).getBody().get("status")).isEqualTo("UNDER_REVIEW");

        Map<?, ?> bundle = get("/api/v1/disputes/" + disputeId + "/evidence-bundle").getBody();
        assertThat(bundle.keySet().toString()).contains("dispute", "transactionTimeline", "evidenceRequirements", "moderationNotes");
        assertThat(getList("/api/v1/disputes/" + disputeId + "/deadlines").getBody()).isNotEmpty();
        assertThat(countRows("select count(*) from marketplace_events where event_type = 'EVIDENCE_BUNDLE_GENERATED'")).isPositive();
    }
}
