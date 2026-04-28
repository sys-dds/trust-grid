package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MarketplaceOutboxAndIdempotencyIntegrationTest extends ParticipantIntegrationTestSupport {

    @Test
    void outboxFieldsAndIdempotencyConflictsProtectDomainMutations() {
        Map<?, ?> first = createParticipant("outbox-idempotency-helper", "Outbox Helper", "outbox-create-1");
        UUID participantId = participantId(first);
        Map<?, ?> repeated = createParticipant("outbox-idempotency-helper", "Outbox Helper", "outbox-create-1");
        assertThat(repeated.get("participantId")).isEqualTo(participantId.toString());

        var conflict = post("/api/v1/participants", Map.of(
                "profileSlug", "outbox-idempotency-changed",
                "displayName", "Changed Payload",
                "createdBy", "operator@example.com",
                "reason", "Changed request"
        ), "outbox-create-1");
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(countRows("select count(*) from participants where profile_slug like 'outbox-idempotency%'")).isEqualTo(1);

        var missingKey = post("/api/v1/participants/" + participantId + "/capabilities", Map.of(
                "capability", "BUY",
                "actor", "operator@example.com",
                "reason", "Missing idempotency proof"
        ), null);
        assertThat(missingKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        post("/api/v1/participants/" + participantId + "/capabilities", Map.of(
                "capability", "BUY",
                "actor", "operator@example.com",
                "reason", "Approved"
        ), "outbox-capability-1");
        var duplicateActiveGrant = post("/api/v1/participants/" + participantId + "/capabilities", Map.of(
                "capability", "BUY",
                "actor", "operator@example.com",
                "reason", "Approved again"
        ), "outbox-capability-2");
        assertThat(duplicateActiveGrant.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        Map<String, Object> outbox = jdbcTemplate.queryForMap("""
                select event_key, event_type, event_status, publish_attempts, published_at, last_error
                from marketplace_events
                where participant_id = ?
                order by created_at
                limit 1
                """, participantId);
        assertThat(outbox.get("event_key")).isNotNull();
        assertThat(outbox.get("event_type")).isNotNull();
        assertThat(outbox).containsEntry("event_status", "PENDING").containsEntry("publish_attempts", 0);
        assertThat(outbox.get("published_at")).isNull();
        assertThat(outbox.get("last_error")).isNull();
        assertThat(countRows("select count(*) from idempotency_records where scope = 'participant:create' and idempotency_key = 'outbox-create-1'")).isEqualTo(1);
        assertThat(countRows("select count(*) from marketplace_events where participant_id = ? and event_type = 'PARTICIPANT_CREATED'", participantId)).isEqualTo(1);
    }
}
