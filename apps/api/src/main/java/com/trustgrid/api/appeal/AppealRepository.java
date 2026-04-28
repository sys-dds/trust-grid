package com.trustgrid.api.appeal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AppealRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AppealRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    UUID create(UUID participantId, CreateAppealRequest request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into appeals (id, participant_id, target_type, target_id, appeal_reason, metadata_json)
                values (?, ?, ?, ?, ?, cast(? as jsonb))
                """, id, participantId, request.targetType(), request.targetId(), request.appealReason(),
                json(request.metadata() == null ? Map.of() : request.metadata()));
        return id;
    }

    void status(UUID appealId, String status) {
        jdbcTemplate.update("update appeals set status = ? where id = ?", status, appealId);
    }

    void decide(UUID appealId, DecideAppealRequest request) {
        jdbcTemplate.update("""
                update appeals set status = 'DECIDED', decision = ?, decided_by = ?, decision_reason = ?, decided_at = now()
                where id = ?
                """, request.decision(), request.decidedBy(), request.reason(), appealId);
    }

    Map<String, Object> capabilityById(UUID capabilityId) {
        return jdbcTemplate.queryForMap("select id, participant_id, capability, status from participant_capabilities where id = ?", capabilityId);
    }

    Map<String, Object> restrictionById(UUID restrictionId) {
        return jdbcTemplate.queryForMap("select id, participant_id, restriction_type, status from participant_restrictions where id = ?", restrictionId);
    }

    void restoreCapability(UUID participantId, String capability, String actor, String reason) {
        jdbcTemplate.update("""
                update participant_capabilities
                set status = 'ACTIVE', updated_at = now(), granted_by = ?, grant_reason = ?,
                    revoked_by = null, revoke_reason = null, restricted_by = null, restrict_reason = null
                where participant_id = ? and capability = ?
                """, actor, reason, participantId, capability);
    }

    void reduceRestriction(UUID participantId, UUID restrictionId, String actor, String reason) {
        jdbcTemplate.update("""
                update participant_restrictions
                set status = 'REMOVED', removed_at = now(), removed_by = ?, remove_reason = ?
                where id = ? and participant_id = ? and status = 'ACTIVE'
                """, actor, reason, restrictionId, participantId);
    }

    UUID createEvidenceRequirement(String targetType, UUID targetId, String reason) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into evidence_requirements (id, target_type, target_id, evidence_type, required_before_action, reason)
                values (?, ?, ?, 'USER_STATEMENT', 'APPEAL_DECISION', ?)
                """, id, targetType, targetId, reason);
        return id;
    }

    void suspendParticipant(UUID participantId, String actor, String reason) {
        jdbcTemplate.update("update participants set account_status = 'SUSPENDED', updated_at = now() where id = ?", participantId);
        jdbcTemplate.update("""
                insert into participant_status_history (id, participant_id, old_status, new_status, actor, reason, metadata_json)
                values (?, ?, null, 'SUSPENDED', ?, ?, '{"source":"appeal_decision"}'::jsonb)
                """, UUID.randomUUID(), participantId, actor, reason);
    }

    void mergeMetadata(UUID appealId, Map<String, Object> metadata) {
        jdbcTemplate.update("""
                update appeals set metadata_json = metadata_json || cast(? as jsonb) where id = ?
                """, json(metadata), appealId);
    }

    AppealResponse get(UUID appealId) {
        return jdbcTemplate.queryForObject("select * from appeals where id = ?", this::row, appealId);
    }

    List<AppealResponse> list(String status) {
        if (status == null || status.isBlank()) {
            return jdbcTemplate.query("select * from appeals order by created_at desc", this::row);
        }
        return jdbcTemplate.query("select * from appeals where status = ? order by created_at desc", this::row, status);
    }

    private AppealResponse row(ResultSet rs, int rowNum) throws SQLException {
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new AppealResponse(rs.getObject("id", UUID.class), rs.getObject("participant_id", UUID.class),
                rs.getString("target_type"), rs.getObject("target_id", UUID.class), rs.getString("status"),
                rs.getString("appeal_reason"), rs.getString("decision"), rs.getString("decided_by"),
                rs.getString("decision_reason"), rs.getTimestamp("created_at").toInstant(),
                decidedAt == null ? null : decidedAt.toInstant(), readMap(rs.getString("metadata_json")));
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }
}
