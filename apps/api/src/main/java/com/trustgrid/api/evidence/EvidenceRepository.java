package com.trustgrid.api.evidence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EvidenceRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EvidenceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    UUID insertEvidence(CreateEvidenceRequest request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into marketplace_evidence (
                    id, target_type, target_id, uploaded_by_participant_id, evidence_type,
                    object_key, evidence_hash, captured_at, metadata_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """, id, request.targetType().name(), request.targetId(), request.uploadedByParticipantId(),
                request.evidenceType().name(), request.objectKey(), request.evidenceHash(),
                request.capturedAt() == null ? null : Timestamp.from(request.capturedAt()),
                json(request.metadata() == null ? Map.of() : request.metadata()));
        return id;
    }

    public UUID createRequirement(EvidenceTargetType targetType, UUID targetId, EvidenceType evidenceType,
                                  String requiredBeforeAction, String reason) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into evidence_requirements (
                    id, target_type, target_id, evidence_type, required_before_action, reason
                ) values (?, ?, ?, ?, ?, ?)
                """, id, targetType.name(), targetId, evidenceType.name(), requiredBeforeAction, reason);
        return id;
    }

    void satisfyRequirement(UUID requirementId, UUID evidenceId) {
        jdbcTemplate.update("""
                update evidence_requirements
                set satisfied = true, satisfied_by_evidence_id = ?, updated_at = now()
                where id = ?
                """, evidenceId, requirementId);
    }

    Optional<EvidenceResponse> findEvidence(UUID evidenceId) {
        return jdbcTemplate.query("""
                select id, target_type, target_id, uploaded_by_participant_id, evidence_type, object_key,
                       evidence_hash, captured_at, metadata_json, created_at
                from marketplace_evidence
                where id = ?
                """, this::evidenceRow, evidenceId).stream().findFirst();
    }

    Optional<EvidenceRequirementResponse> findRequirement(UUID requirementId) {
        return jdbcTemplate.query("""
                select id, target_type, target_id, evidence_type, required_before_action, satisfied,
                       satisfied_by_evidence_id, reason, created_at, updated_at
                from evidence_requirements
                where id = ?
                """, this::requirementRow, requirementId).stream().findFirst();
    }

    List<EvidenceResponse> searchEvidence(EvidenceTargetType targetType, UUID targetId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select id, target_type, target_id, uploaded_by_participant_id, evidence_type, object_key,
                       evidence_hash, captured_at, metadata_json, created_at
                from marketplace_evidence
                where 1 = 1
                """);
        if (targetType != null) {
            sql.append(" and target_type = ? ");
            args.add(targetType.name());
        }
        if (targetId != null) {
            sql.append(" and target_id = ? ");
            args.add(targetId);
        }
        sql.append(" order by created_at asc ");
        return jdbcTemplate.query(sql.toString(), this::evidenceRow, args.toArray());
    }

    List<EvidenceRequirementResponse> searchRequirements(EvidenceTargetType targetType, UUID targetId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select id, target_type, target_id, evidence_type, required_before_action, satisfied,
                       satisfied_by_evidence_id, reason, created_at, updated_at
                from evidence_requirements
                where 1 = 1
                """);
        if (targetType != null) {
            sql.append(" and target_type = ? ");
            args.add(targetType.name());
        }
        if (targetId != null) {
            sql.append(" and target_id = ? ");
            args.add(targetId);
        }
        sql.append(" order by created_at asc ");
        return jdbcTemplate.query(sql.toString(), this::requirementRow, args.toArray());
    }

    boolean targetExists(EvidenceTargetType targetType, UUID targetId) {
        String table = switch (targetType) {
            case LISTING -> "marketplace_listings";
            case TRANSACTION -> "marketplace_transactions";
            case DISPUTE -> "marketplace_disputes";
            case PARTICIPANT -> "participants";
        };
        Integer count = jdbcTemplate.queryForObject("select count(*) from " + table + " where id = ?", Integer.class, targetId);
        return count != null && count > 0;
    }

    private EvidenceResponse evidenceRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp capturedAt = rs.getTimestamp("captured_at");
        return new EvidenceResponse(
                rs.getObject("id", UUID.class),
                EvidenceTargetType.valueOf(rs.getString("target_type")),
                rs.getObject("target_id", UUID.class),
                rs.getObject("uploaded_by_participant_id", UUID.class),
                EvidenceType.valueOf(rs.getString("evidence_type")),
                rs.getString("object_key"),
                rs.getString("evidence_hash"),
                capturedAt == null ? null : capturedAt.toInstant(),
                readMap(rs.getString("metadata_json")),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private EvidenceRequirementResponse requirementRow(ResultSet rs, int rowNum) throws SQLException {
        return new EvidenceRequirementResponse(
                rs.getObject("id", UUID.class),
                EvidenceTargetType.valueOf(rs.getString("target_type")),
                rs.getObject("target_id", UUID.class),
                EvidenceType.valueOf(rs.getString("evidence_type")),
                rs.getString("required_before_action"),
                rs.getBoolean("satisfied"),
                rs.getObject("satisfied_by_evidence_id", UUID.class),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON payload", exception);
        }
    }

    Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }
}
