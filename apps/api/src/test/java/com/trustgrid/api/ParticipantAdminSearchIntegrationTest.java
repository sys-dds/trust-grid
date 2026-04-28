package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ParticipantAdminSearchIntegrationTest extends ParticipantIntegrationTestSupport {

    @Test
    void adminSearchFiltersParticipantsByIdentityTrustCapabilityAndRestriction() {
        UUID targetId = participantId(createParticipant("admin-search-target", "Admin Search Target", "admin-create-target"));
        createParticipant("admin-search-other", "Other Participant", "admin-create-other");

        post("/api/v1/participants/" + targetId + "/capabilities", Map.of(
                "capability", "OFFER_SERVICES",
                "actor", "operator@example.com",
                "reason", "Approved"
        ), "admin-grant-1");
        post("/api/v1/participants/" + targetId + "/verification", Map.of(
                "newStatus", "BASIC",
                "actor", "operator@example.com",
                "reason", "Observed contact methods"
        ), "admin-verification-1");
        post("/api/v1/participants/" + targetId + "/account-status", Map.of(
                "newStatus", "LIMITED",
                "actor", "operator@example.com",
                "reason", "Temporary limits"
        ), "admin-status-1");
        post("/api/v1/participants/" + targetId + "/restrictions", Map.of(
                "restrictionType", "REQUIRES_MANUAL_REVIEW",
                "actor", "operator@example.com",
                "reason", "Review required"
        ), "admin-restriction-1");

        assertSearchFinds("/api/v1/admin/participants?query=target", targetId);
        assertSearchFinds("/api/v1/admin/participants?profileSlug=admin-search-target", targetId);
        assertSearchFinds("/api/v1/admin/participants?displayName=Target", targetId);
        assertSearchFinds("/api/v1/admin/participants?accountStatus=LIMITED", targetId);
        assertSearchFinds("/api/v1/admin/participants?trustTier=LIMITED", targetId);
        assertSearchFinds("/api/v1/admin/participants?verificationStatus=BASIC", targetId);
        assertSearchFinds("/api/v1/admin/participants?capability=OFFER_SERVICES", targetId);
        assertSearchFinds("/api/v1/admin/participants?restricted=true", targetId);
        assertThat(get("/api/v1/admin/participants?limit=500").getBody()).containsEntry("limit", 100);
    }

    private void assertSearchFinds(String path, UUID participantId) {
        Map<?, ?> response = get(path).getBody();
        List<Object> participants = list(response, "participants");
        List<Object> participantIds = participants.stream()
                .map(item -> (Object) ((Map<?, ?>) item).get("participantId"))
                .toList();
        assertThat(participantIds).contains(participantId.toString());
    }
}
