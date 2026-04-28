package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ParticipantIdentityFlowIntegrationTest extends ParticipantIntegrationTestSupport {

    @Test
    void participantCreateGetSearchUniquenessAndDefaultTrustIdentityWork() {
        Map<?, ?> created = createParticipant("identity-flow-helper", "Identity Flow Helper", "identity-create-1");
        UUID participantId = participantId(created);

        Map<?, ?> repeated = createParticipant("identity-flow-helper", "Identity Flow Helper", "identity-create-1");
        assertThat(repeated.get("participantId")).isEqualTo(participantId.toString());

        assertThat(get("/api/v1/participants/" + participantId).getBody().get("profileSlug")).isEqualTo("identity-flow-helper");
        assertThat(get("/api/v1/participants/by-slug/identity-flow-helper").getBody().get("participantId")).isEqualTo(participantId.toString());

        Map<?, ?> search = get("/api/v1/participants?profileSlug=identity-flow-helper").getBody();
        assertThat(list(search, "participants")).hasSize(1);

        assertThat(created.get("accountStatus")).isEqualTo("ACTIVE");
        assertThat(created.get("verificationStatus")).isEqualTo("UNVERIFIED");
        assertThat(created.get("trustTier")).isEqualTo("NEW");
        assertThat(created.get("riskLevel")).isEqualTo("LOW");

        assertThat(countRows("select count(*) from trust_profiles where participant_id = ?", participantId)).isEqualTo(1);
        assertThat(countRows("select count(*) from marketplace_events where participant_id = ? and event_type = 'PARTICIPANT_CREATED'", participantId)).isEqualTo(1);
        assertThat(countRows("select count(*) from marketplace_events where participant_id = ? and event_type = 'TRUST_PROFILE_INITIALIZED'", participantId)).isEqualTo(1);

        var duplicate = post("/api/v1/participants", Map.of(
                "profileSlug", "identity-flow-helper",
                "displayName", "Different Helper",
                "createdBy", "operator@example.com",
                "reason", "Duplicate slug check"
        ), "identity-create-2");
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
