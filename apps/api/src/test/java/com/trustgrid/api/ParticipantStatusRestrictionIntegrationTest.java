package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ParticipantStatusRestrictionIntegrationTest extends ParticipantIntegrationTestSupport {

    @Test
    void statusTransitionsRestrictionsAndCapabilityBlockingWork() {
        UUID participantId = participantId(createParticipant("status-restriction-helper", "Status Restriction Helper", "status-create-1"));

        post("/api/v1/participants/" + participantId + "/capabilities", Map.of(
                "capability", "BUY",
                "actor", "operator@example.com",
                "reason", "Approved to buy"
        ), "status-grant-buy-1");
        post("/api/v1/participants/" + participantId + "/account-status", Map.of(
                "newStatus", "LIMITED",
                "actor", "operator@example.com",
                "reason", "Temporary limits"
        ), "status-limited-1");
        post("/api/v1/participants/" + participantId + "/account-status", Map.of(
                "newStatus", "ACTIVE",
                "actor", "operator@example.com",
                "reason", "Limits cleared"
        ), "status-active-1");
        post("/api/v1/participants/" + participantId + "/account-status", Map.of(
                "newStatus", "SUSPENDED",
                "actor", "operator@example.com",
                "reason", "Safety review"
        ), "status-suspended-1");

        var blockedGrant = post("/api/v1/participants/" + participantId + "/capabilities", Map.of(
                "capability", "OFFER_SERVICES",
                "actor", "operator@example.com",
                "reason", "Should be blocked while suspended"
        ), "status-blocked-grant-1");
        assertThat(blockedGrant.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Map<?, ?> applied = post("/api/v1/participants/" + participantId + "/restrictions", Map.of(
                "restrictionType", "REQUIRES_MANUAL_REVIEW",
                "actor", "operator@example.com",
                "reason", "Repeated risky behavior"
        ), "status-restriction-1").getBody();
        Map<?, ?> summaryWithRestriction = get("/api/v1/participants/" + participantId + "/trust-summary").getBody();
        assertThat(list(summaryWithRestriction, "activeRestrictions")).contains("REQUIRES_MANUAL_REVIEW");
        assertThat(childMap(summaryWithRestriction, "marketplaceEligibility")).containsEntry("requiresManualReview", true);

        post("/api/v1/participants/" + participantId + "/restrictions/" + applied.get("id") + "/remove", Map.of(
                "actor", "operator@example.com",
                "reason", "Restriction no longer needed"
        ), "status-remove-restriction-1");
        assertThat(list(get("/api/v1/participants/" + participantId + "/trust-summary").getBody(), "activeRestrictions")).isEmpty();

        post("/api/v1/participants/" + participantId + "/account-status", Map.of(
                "newStatus", "CLOSED",
                "actor", "operator@example.com",
                "reason", "Account closure"
        ), "status-closed-1");
        var terminalTransition = post("/api/v1/participants/" + participantId + "/account-status", Map.of(
                "newStatus", "ACTIVE",
                "actor", "operator@example.com",
                "reason", "Should fail"
        ), "status-terminal-1");
        assertThat(terminalTransition.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(countRows("select count(*) from participant_status_history where participant_id = ?", participantId)).isEqualTo(4);
        assertThat(countRows("select count(*) from marketplace_events where participant_id = ? and event_type = 'RESTRICTION_APPLIED'", participantId)).isEqualTo(1);
        assertThat(countRows("select count(*) from marketplace_events where participant_id = ? and event_type = 'RESTRICTION_REMOVED'", participantId)).isEqualTo(1);
    }

    @Test
    void searchEligibilityReflectsSuspendedClosedAndHiddenParticipants() {
        UUID active = participantId(createParticipant("search-active-helper", "Search Active Helper", "search-active-create"));
        post("/api/v1/participants/" + active + "/capabilities", Map.of(
                "capability", "BUY",
                "actor", "operator@example.com",
                "reason", "Approved"
        ), "search-active-buy");
        assertThat(childMap(get("/api/v1/participants/" + active + "/trust-summary").getBody(), "marketplaceEligibility"))
                .containsEntry("canAppearInSearch", true);

        UUID suspended = participantId(createParticipant("search-suspended-helper", "Search Suspended Helper", "search-suspended-create"));
        post("/api/v1/participants/" + suspended + "/account-status", Map.of(
                "newStatus", "SUSPENDED",
                "actor", "operator@example.com",
                "reason", "Safety review"
        ), "search-suspended-status");
        assertThat(childMap(get("/api/v1/participants/" + suspended + "/trust-summary").getBody(), "marketplaceEligibility"))
                .containsEntry("canAppearInSearch", false);

        UUID closed = participantId(createParticipant("search-closed-helper", "Search Closed Helper", "search-closed-create"));
        post("/api/v1/participants/" + closed + "/account-status", Map.of(
                "newStatus", "CLOSED",
                "actor", "operator@example.com",
                "reason", "Closed"
        ), "search-closed-status");
        assertThat(childMap(get("/api/v1/participants/" + closed + "/trust-summary").getBody(), "marketplaceEligibility"))
                .containsEntry("canAppearInSearch", false);

        post("/api/v1/participants/" + active + "/restrictions", Map.of(
                "restrictionType", "HIDDEN_FROM_MARKETPLACE_SEARCH",
                "actor", "operator@example.com",
                "reason", "Hide profile"
        ), "search-hidden-restriction");
        assertThat(childMap(get("/api/v1/participants/" + active + "/trust-summary").getBody(), "marketplaceEligibility"))
                .containsEntry("canAppearInSearch", false);
    }
}
