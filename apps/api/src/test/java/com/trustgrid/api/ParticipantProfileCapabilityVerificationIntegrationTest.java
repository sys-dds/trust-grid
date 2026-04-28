package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ParticipantProfileCapabilityVerificationIntegrationTest extends ParticipantIntegrationTestSupport {

    @Test
    void profileCapabilitiesVerificationAndTrustSummaryStayAligned() {
        UUID participantId = participantId(createParticipant("profile-capability-helper", "Profile Capability Helper", "pcv-create-1"));

        Map<?, ?> profile = patch("/api/v1/participants/" + participantId + "/profile", Map.of(
                "displayName", "Trusted Local Helper",
                "bio", "I help with local services.",
                "locationSummary", "Glasgow area",
                "capabilityDescription", "Local services",
                "profilePhotoObjectKey", "placeholder/profile-photo-key",
                "updatedBy", "operator@example.com",
                "reason", "Participant completed profile"
        ), "pcv-profile-1").getBody();
        assertThat((Integer) profile.get("profileCompletenessScore")).isGreaterThan(0);

        post("/api/v1/participants/" + participantId + "/capabilities", Map.of(
                "capability", "OFFER_SERVICES",
                "actor", "operator@example.com",
                "reason", "Approved to offer marketplace services"
        ), "pcv-grant-offer-1");
        post("/api/v1/participants/" + participantId + "/capabilities/OFFER_SERVICES/revoke", Map.of(
                "actor", "operator@example.com",
                "reason", "Capability removed after review"
        ), "pcv-revoke-offer-1");
        post("/api/v1/participants/" + participantId + "/capabilities", Map.of(
                "capability", "BUY",
                "actor", "operator@example.com",
                "reason", "Approved to buy"
        ), "pcv-grant-buy-1");
        post("/api/v1/participants/" + participantId + "/capabilities/BUY/restrict", Map.of(
                "actor", "operator@example.com",
                "reason", "Temporarily restricted after review"
        ), "pcv-restrict-buy-1");
        post("/api/v1/participants/" + participantId + "/verification", Map.of(
                "newStatus", "BASIC",
                "actor", "operator@example.com",
                "reason", "Email and phone observed"
        ), "pcv-verification-1");

        Map<?, ?> summary = get("/api/v1/participants/" + participantId + "/trust-summary").getBody();
        assertThat(summary.get("verificationStatus")).isEqualTo("BASIC");
        assertThat(list(summary, "revokedCapabilities")).contains("OFFER_SERVICES");
        assertThat(list(summary, "restrictedCapabilities")).contains("BUY");
        assertThat(countRows("select count(*) from marketplace_events where participant_id = ? and event_type = 'PROFILE_UPDATED'", participantId)).isEqualTo(1);
        assertThat(countRows("select count(*) from marketplace_events where participant_id = ? and event_type = 'CAPABILITY_GRANTED'", participantId)).isEqualTo(2);
        assertThat(countRows("select count(*) from marketplace_events where participant_id = ? and event_type = 'CAPABILITY_REVOKED'", participantId)).isEqualTo(1);
        assertThat(countRows("select count(*) from marketplace_events where participant_id = ? and event_type = 'CAPABILITY_RESTRICTED'", participantId)).isEqualTo(1);
        assertThat(countRows("select count(*) from participant_verification_history where participant_id = ?", participantId)).isEqualTo(1);
    }
}
