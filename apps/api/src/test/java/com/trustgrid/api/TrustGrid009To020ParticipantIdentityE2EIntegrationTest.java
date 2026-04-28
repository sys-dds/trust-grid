package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustGrid009To020ParticipantIdentityE2EIntegrationTest extends ParticipantIntegrationTestSupport {

    @Test
    void participantIdentityLifecycleWorksEndToEnd() {
        Map<?, ?> created = createParticipant("tg009020-e2e-helper", "TG009020 Helper", "e2e-create-1");
        UUID participantId = participantId(created);
        assertThat(createParticipant("tg009020-e2e-helper", "TG009020 Helper", "e2e-create-1").get("participantId"))
                .isEqualTo(participantId.toString());

        patch("/api/v1/participants/" + participantId + "/profile", Map.of(
                "displayName", "TG009020 Trusted Helper",
                "bio", "I help with local services.",
                "locationSummary", "Glasgow area",
                "capabilityDescription", "Local services",
                "profilePhotoObjectKey", "placeholder/profile-photo-key",
                "updatedBy", "operator@example.com",
                "reason", "Participant completed profile"
        ), "e2e-profile-1");
        post("/api/v1/participants/" + participantId + "/capabilities", Map.of(
                "capability", "OFFER_SERVICES",
                "actor", "operator@example.com",
                "reason", "Approved to offer marketplace services"
        ), "e2e-grant-offer-1");
        post("/api/v1/participants/" + participantId + "/capabilities/ACCEPT_ERRANDS/restrict", Map.of(
                "actor", "operator@example.com",
                "reason", "Temporarily restricted after review"
        ), "e2e-restrict-errands-1");
        post("/api/v1/participants/" + participantId + "/verification", Map.of(
                "newStatus", "BASIC",
                "actor", "operator@example.com",
                "reason", "Email and phone observed"
        ), "e2e-verification-1");
        post("/api/v1/participants/" + participantId + "/account-status", Map.of(
                "newStatus", "LIMITED",
                "actor", "operator@example.com",
                "reason", "Risk review requires temporary limits"
        ), "e2e-status-1");
        post("/api/v1/participants/" + participantId + "/restrictions", Map.of(
                "restrictionType", "REQUIRES_MANUAL_REVIEW",
                "actor", "operator@example.com",
                "reason", "Repeated risky behavior"
        ), "e2e-restriction-1");

        Map<?, ?> summary = get("/api/v1/participants/" + participantId + "/trust-summary").getBody();
        assertThat(summary.get("accountStatus")).isEqualTo("LIMITED");
        assertThat(summary.get("verificationStatus")).isEqualTo("BASIC");
        assertThat(summary.get("trustTier")).isEqualTo("LIMITED");
        assertThat(list(summary, "activeCapabilities")).contains("OFFER_SERVICES");
        assertThat(list(summary, "restrictedCapabilities")).contains("ACCEPT_ERRANDS");
        assertThat(list(summary, "activeRestrictions")).contains("REQUIRES_MANUAL_REVIEW");
        assertThat(childMap(summary, "marketplaceEligibility")).containsEntry("requiresManualReview", true);

        List<Object> events = list(get("/api/v1/participants/" + participantId + "/timeline").getBody(), "events");
        assertEventTypes(events,
                "PARTICIPANT_CREATED",
                "TRUST_PROFILE_INITIALIZED",
                "PROFILE_UPDATED",
                "CAPABILITY_GRANTED",
                "CAPABILITY_RESTRICTED",
                "VERIFICATION_STATUS_UPDATED",
                "ACCOUNT_STATUS_UPDATED",
                "RESTRICTION_APPLIED");
        events.forEach(event -> {
            Map<?, ?> row = (Map<?, ?>) event;
            assertThat(row.get("eventKey")).isNotNull();
            assertThat(row.get("eventStatus")).isEqualTo("PENDING");
            assertThat(row.get("publishAttempts")).isEqualTo(0);
            assertThat(row.get("publishedAt")).isNull();
        });

        List<Object> adminParticipants = list(get("/api/v1/admin/participants?profileSlug=tg009020-e2e-helper").getBody(), "participants");
        assertThat(adminParticipants.stream().anyMatch(item -> participantId.toString().equals(((Map<?, ?>) item).get("participantId")))).isTrue();
        assertThat(get("/actuator/health").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/actuator/health/readiness").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/api/v1/system/ping").getStatusCode().is2xxSuccessful()).isTrue();
    }

    private void assertEventTypes(List<Object> events, String... expectedTypes) {
        List<Object> eventTypes = events.stream()
                .map(event -> (Object) ((Map<?, ?>) event).get("eventType"))
                .toList();
        assertThat(eventTypes).containsAll(List.of((Object[]) expectedTypes));
    }
}
