package com.trustgrid.api.ops;

import com.trustgrid.api.shared.OutboxRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountRestrictionWorkflowController {
    private final JdbcTemplate jdbcTemplate;
    private final OutboxRepository outboxRepository;

    public AccountRestrictionWorkflowController(JdbcTemplate jdbcTemplate, OutboxRepository outboxRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxRepository = outboxRepository;
    }

    @PostMapping("/api/v1/ops/account-restrictions/apply")
    public Map<String, Object> apply(@RequestBody Map<String, Object> request) {
        UUID participantId = UUID.fromString(required(request, "participantId"));
        UUID restrictionId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into participant_restrictions (id, participant_id, restriction_type, status, actor, reason, metadata_json)
                values (?, ?, ?, 'ACTIVE', ?, ?, cast(? as jsonb))
                """, restrictionId, participantId, required(request, "restrictionType"), required(request, "actor"),
                required(request, "reason"), "{\"workflow\":\"ops\"}");
        outboxRepository.insert("PARTICIPANT", participantId, participantId, "ACCOUNT_RESTRICTION_WORKFLOW_UPDATED",
                Map.of("restrictionId", restrictionId, "action", "apply"));
        return Map.of("restrictionId", restrictionId, "status", "ACTIVE");
    }

    @PostMapping("/api/v1/ops/account-restrictions/restore")
    public Map<String, Object> restore(@RequestBody Map<String, Object> request) {
        UUID participantId = UUID.fromString(required(request, "participantId"));
        Object restriction = request.get("restrictionId");
        int updated;
        if (restriction != null && !restriction.toString().isBlank()) {
            UUID restrictionId = UUID.fromString(restriction.toString());
            updated = jdbcTemplate.update("""
                    update participant_restrictions
                    set status = 'REMOVED', removed_at = now(), removed_by = ?, remove_reason = ?
                    where id = ? and participant_id = ? and status = 'ACTIVE'
                    """, required(request, "actor"), required(request, "reason"), restrictionId, participantId);
            outboxRepository.insert("PARTICIPANT", participantId, participantId, "ACCOUNT_RESTRICTION_WORKFLOW_UPDATED",
                    Map.of("action", "restore", "restrictionId", restrictionId, "updated", updated));
            return Map.of("participantId", participantId, "restrictionId", restrictionId, "updated", updated);
        }
        jdbcTemplate.update("""
                update participant_restrictions
                set status = 'REMOVED', removed_at = now(), removed_by = ?, remove_reason = ?
                where participant_id = ? and restriction_type = ? and status = 'ACTIVE'
                """, required(request, "actor"), required(request, "reason"), participantId, required(request, "restrictionType"));
        outboxRepository.insert("PARTICIPANT", participantId, participantId, "ACCOUNT_RESTRICTION_WORKFLOW_UPDATED",
                Map.of("action", "restore"));
        return Map.of("participantId", participantId, "status", "RESTORED");
    }

    private String required(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }
}
