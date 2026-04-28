package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

abstract class EvidenceDisputeRiskIntegrationTestSupport extends MarketplaceLifecycleIntegrationTestSupport {

    Flow createCompletedServiceFlow(String prefix) {
        UUID buyer = createCapableParticipant(prefix + "-buyer-" + suffix(), "Buyer " + prefix, "BUY");
        UUID provider = createCapableParticipant(prefix + "-provider-" + suffix(), "Provider " + prefix, "OFFER_SERVICES");
        UUID listing = createListing(provider, "SERVICE_OFFER", "TUTORING", prefix + " service " + suffix(), 2500L, null, serviceDetails());
        publish(listing);
        UUID tx = createTransaction(listing, buyer, provider, prefix + "-tx-" + suffix());
        post("/api/v1/transactions/" + tx + "/start", action(provider), prefix + "-start-" + suffix());
        post("/api/v1/transactions/" + tx + "/claim-completion", action(provider), prefix + "-claim-" + suffix());
        post("/api/v1/transactions/" + tx + "/confirm-completion", action(buyer), prefix + "-confirm-" + suffix());
        return new Flow(buyer, provider, listing, tx);
    }

    Flow createDisputableServiceFlow(String prefix) {
        UUID buyer = createCapableParticipant(prefix + "-buyer-" + suffix(), "Buyer " + prefix, "BUY");
        UUID provider = createCapableParticipant(prefix + "-provider-" + suffix(), "Provider " + prefix, "OFFER_SERVICES");
        UUID listing = createListing(provider, "SERVICE_OFFER", "TUTORING", prefix + " service " + suffix(), 2500L, null, serviceDetails());
        publish(listing);
        UUID tx = createTransaction(listing, buyer, provider, prefix + "-tx-" + suffix());
        post("/api/v1/transactions/" + tx + "/start", action(provider), prefix + "-start-" + suffix());
        post("/api/v1/transactions/" + tx + "/claim-completion", action(provider), prefix + "-claim-" + suffix());
        return new Flow(buyer, provider, listing, tx);
    }

    UUID openDispute(Flow flow, String key) {
        ResponseEntity<Map> response = post("/api/v1/transactions/" + flow.transactionId() + "/disputes", Map.of(
                "openedByParticipantId", flow.buyerId().toString(),
                "disputeType", "SERVICE_NOT_DELIVERED",
                "reason", "Service completion was challenged",
                "metadata", Map.of("source", "integration")
        ), key);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString((String) response.getBody().get("disputeId"));
    }

    UUID recordEvidence(String targetType, UUID targetId, UUID participantId, String evidenceType, String key) {
        ResponseEntity<Map> response = post("/api/v1/evidence", Map.of(
                "targetType", targetType,
                "targetId", targetId.toString(),
                "uploadedByParticipantId", participantId.toString(),
                "evidenceType", evidenceType,
                "objectKey", "placeholder/" + key,
                "evidenceHash", "sha256-" + key,
                "capturedAt", "2026-05-01T10:00:00Z",
                "metadata", Map.of("metadataOnly", true),
                "actor", "participant@example.com",
                "reason", "Evidence metadata recorded"
        ), key);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString((String) response.getBody().get("evidenceId"));
    }

    Map<String, Object> action(UUID participantId) {
        return Map.of("actorParticipantId", participantId.toString(), "actor", "participant@example.com", "reason", "Transaction action");
    }

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    record Flow(UUID buyerId, UUID providerId, UUID listingId, UUID transactionId) {
    }
}
