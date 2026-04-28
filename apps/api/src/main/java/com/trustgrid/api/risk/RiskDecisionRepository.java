package com.trustgrid.api.risk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RiskDecisionRepository {

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RiskDecisionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    UUID insert(RiskTargetType targetType, UUID targetId, int score, String riskLevel, RiskDecision decision,
                List<String> reasons, Map<String, Object> snapshot, String policyVersion) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into risk_decisions (
                    id, target_type, target_id, score, risk_level, decision, reasons_json, snapshot_json, policy_version
                ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?)
                """, id, targetType.name(), targetId, score, riskLevel, decision.name(), json(reasons), json(snapshot), policyVersion);
        return id;
    }

    UUID insertOffPlatformReport(UUID transactionId, OffPlatformContactReportRequest request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into off_platform_contact_reports (
                    id, transaction_id, reporter_participant_id, reported_participant_id, report_text, status
                ) values (?, ?, ?, ?, ?, 'ACTION_RECOMMENDED')
                """, id, transactionId, request.reporterParticipantId(), request.reportedParticipantId(), request.reportText());
        return id;
    }

    UUID insertSyntheticSignal(UUID participantId, SyntheticRiskSignalRequest request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into synthetic_risk_signals (
                    id, participant_id, signal_type, signal_hash, risk_weight, source, retention_until
                ) values (?, ?, ?, ?, ?, ?, ?)
                """, id, participantId, request.signalType(), request.signalHash(), request.riskWeight(),
                request.source(), request.retentionUntil() == null ? null : Timestamp.from(request.retentionUntil()));
        return id;
    }

    boolean transactionParticipant(UUID transactionId, UUID participantId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from marketplace_transactions
                where id = ? and (requester_participant_id = ? or provider_participant_id = ?)
                """, Integer.class, transactionId, participantId, participantId);
        return count != null && count > 0;
    }

    int repeatDisputes(UUID participantId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from marketplace_disputes d
                join marketplace_transactions t on t.id = d.transaction_id
                where t.requester_participant_id = ? or t.provider_participant_id = ?
                """, Integer.class, participantId, participantId);
        return count == null ? 0 : count;
    }

    Optional<RiskDecisionResponse> find(UUID id) {
        return jdbcTemplate.query("""
                select id, target_type, target_id, score, risk_level, decision, reasons_json, snapshot_json, policy_version, created_at
                from risk_decisions where id = ?
                """, this::row, id).stream().findFirst();
    }

    List<RiskDecisionResponse> search(RiskTargetType targetType, UUID targetId) {
        if (targetType != null && targetId != null) {
            return jdbcTemplate.query("""
                    select id, target_type, target_id, score, risk_level, decision, reasons_json, snapshot_json, policy_version, created_at
                    from risk_decisions where target_type = ? and target_id = ? order by created_at desc
                    """, this::row, targetType.name(), targetId);
        }
        return jdbcTemplate.query("""
                select id, target_type, target_id, score, risk_level, decision, reasons_json, snapshot_json, policy_version, created_at
                from risk_decisions order by created_at desc limit 100
                """, this::row);
    }

    private RiskDecisionResponse row(ResultSet rs, int rowNum) throws SQLException {
        return new RiskDecisionResponse(
                rs.getObject("id", UUID.class),
                RiskTargetType.valueOf(rs.getString("target_type")),
                rs.getObject("target_id", UUID.class),
                rs.getInt("score"),
                rs.getString("risk_level"),
                RiskDecision.valueOf(rs.getString("decision")),
                readList(rs.getString("reasons_json")),
                readMap(rs.getString("snapshot_json")),
                rs.getString("policy_version"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON payload", exception);
        }
    }

    List<String> readList(String value) {
        try {
            return objectMapper.readValue(value, LIST_TYPE);
        } catch (Exception exception) {
            return List.of();
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
