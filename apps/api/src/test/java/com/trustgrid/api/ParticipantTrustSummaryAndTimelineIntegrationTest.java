package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ParticipantTrustSummaryAndTimelineIntegrationTest extends ParticipantIntegrationTestSupport {

    @Test
    void trustSummaryAndTimelineExposeCurrentIdentityState() {
        UUID participantId = participantId(createParticipant("summary-timeline-helper", "Summary Timeline Helper", "summary-create-1"));

        patch("/api/v1/participants/" + participantId + "/profile", Map.of(
                "displayName", "Summary Timeline Helper",
                "bio", "Profile update for timeline",
                "locationSummary", "Glasgow",
                "capabilityDescription", "Local services",
                "profilePhotoObjectKey", "placeholder/photo",
                "updatedBy", "operator@example.com",
                "reason", "Profile update"
        ), "summary-profile-1");
        post("/api/v1/participants/" + participantId + "/capabilities", Map.of(
                "capability", "OFFER_SERVICES",
                "actor", "operator@example.com",
                "reason", "Approved"
        ), "summary-grant-1");
        post("/api/v1/participants/" + participantId + "/verification", Map.of(
                "newStatus", "BASIC",
                "actor", "operator@example.com",
                "reason", "Observed contact methods"
        ), "summary-verification-1");
        post("/api/v1/participants/" + participantId + "/account-status", Map.of(
                "newStatus", "LIMITED",
                "actor", "operator@example.com",
                "reason", "Temporary limit"
        ), "summary-status-1");
        post("/api/v1/participants/" + participantId + "/restrictions", Map.of(
                "restrictionType", "HIDDEN_FROM_MARKETPLACE_SEARCH",
                "actor", "operator@example.com",
                "reason", "Hide during review"
        ), "summary-hidden-1");
        post("/api/v1/participants/" + participantId + "/restrictions", Map.of(
                "restrictionType", "REQUIRES_VERIFICATION",
                "actor", "operator@example.com",
                "reason", "Needs more verification"
        ), "summary-requires-verification-1");

        Map<?, ?> summary = get("/api/v1/participants/" + participantId + "/trust-summary").getBody();
        assertThat(summary.get("accountStatus")).isEqualTo("LIMITED");
        assertThat(summary.get("verificationStatus")).isEqualTo("BASIC");
        assertThat(summary.get("trustTier")).isEqualTo("LIMITED");
        assertThat(summary.get("riskLevel")).isEqualTo("LOW");
        assertThat(summary.get("trustScore")).isEqualTo(500);
        assertThat(summary.get("trustConfidence")).isEqualTo(0);
        assertThat(list(summary, "activeCapabilities")).contains("OFFER_SERVICES");
        assertThat(list(summary, "activeRestrictions")).contains("HIDDEN_FROM_MARKETPLACE_SEARCH", "REQUIRES_VERIFICATION");
        assertThat(childMap(summary, "marketplaceEligibility"))
                .containsEntry("canOfferServices", true)
                .containsEntry("canAppearInSearch", false)
                .containsEntry("requiresVerification", true);

        Map<?, ?> timeline = get("/api/v1/participants/" + participantId + "/timeline?limit=3").getBody();
        assertThat((Integer) timeline.get("limit")).isEqualTo(3);
        assertThat(list(timeline, "events")).hasSizeLessThanOrEqualTo(3);

        Map<?, ?> filtered = get("/api/v1/participants/" + participantId + "/timeline?eventType=CAPABILITY_GRANTED").getBody();
        assertThat(list(filtered, "events")).hasSize(1);
    }
}
