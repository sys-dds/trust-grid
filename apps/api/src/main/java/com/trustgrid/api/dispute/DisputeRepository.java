package com.trustgrid.api.dispute;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DisputeRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DisputeRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    UUID insertDispute(UUID transactionId, CreateDisputeRequest request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into marketplace_disputes (
                    id, transaction_id, opened_by_participant_id, dispute_type, status, reason, metadata_json
                ) values (?, ?, ?, ?, 'OPEN', ?, cast(? as jsonb))
                """, id, transactionId, request.openedByParticipantId(), request.disputeType().name(),
                request.reason(), json(request.metadata() == null ? Map.of() : request.metadata()));
        return id;
    }

    UUID insertStatement(UUID disputeId, DisputeStatementRequest request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into dispute_statements (
                    id, dispute_id, participant_id, statement_type, statement_text, actor, reason, metadata_json
                ) values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """, id, disputeId, request.participantId(), request.statementType().name(),
                request.statementText(), request.actor(), request.reason(),
                json(request.metadata() == null ? Map.of() : request.metadata()));
        return id;
    }

    UUID createDeadline(UUID disputeId, String role) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into dispute_evidence_deadlines (id, dispute_id, required_from_role, due_at)
                values (?, ?, ?, now() + interval '3 days')
                """, id, disputeId, role);
        return id;
    }

    void updateStatus(UUID disputeId, DisputeStatus status) {
        jdbcTemplate.update("""
                update marketplace_disputes set status = ?, updated_at = now() where id = ?
                """, status.name(), disputeId);
    }

    void resolve(UUID disputeId, DisputeStatus status, DisputeOutcome outcome, String resolvedBy, String reason) {
        jdbcTemplate.update("""
                update marketplace_disputes
                set status = ?, outcome = ?, resolved_by = ?, resolution_reason = ?, resolved_at = now(), updated_at = now()
                where id = ?
                """, status.name(), outcome.name(), resolvedBy, reason, disputeId);
    }

    Optional<DisputeResponse> find(UUID disputeId) {
        return jdbcTemplate.query("""
                select id, transaction_id, opened_by_participant_id, dispute_type, status, outcome, reason,
                       resolution_reason, resolved_by, opened_at, updated_at, resolved_at, metadata_json
                from marketplace_disputes where id = ?
                """, this::disputeRow, disputeId).stream().findFirst();
    }

    List<DisputeResponse> search(UUID transactionId, DisputeStatus status) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select id, transaction_id, opened_by_participant_id, dispute_type, status, outcome, reason,
                       resolution_reason, resolved_by, opened_at, updated_at, resolved_at, metadata_json
                from marketplace_disputes
                where 1 = 1
                """);
        if (transactionId != null) {
            sql.append(" and transaction_id = ? ");
            args.add(transactionId);
        }
        if (status != null) {
            sql.append(" and status = ? ");
            args.add(status.name());
        }
        sql.append(" order by opened_at desc ");
        return jdbcTemplate.query(sql.toString(), this::disputeRow, args.toArray());
    }

    List<DisputeStatementResponse> statements(UUID disputeId) {
        return jdbcTemplate.query("""
                select id, dispute_id, participant_id, statement_type, statement_text, actor, reason, created_at, metadata_json
                from dispute_statements where dispute_id = ? order by created_at asc
                """, this::statementRow, disputeId);
    }

    List<DisputeEvidenceDeadlineResponse> deadlines(UUID disputeId) {
        return jdbcTemplate.query("""
                select id, dispute_id, required_from_role, due_at, status, created_at, satisfied_at, missed_at
                from dispute_evidence_deadlines where dispute_id = ? order by created_at asc
                """, this::deadlineRow, disputeId);
    }

    boolean hasActiveDispute(UUID transactionId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from marketplace_disputes
                where transaction_id = ? and status in (
                    'OPEN', 'AWAITING_BUYER_EVIDENCE', 'AWAITING_SELLER_EVIDENCE',
                    'AWAITING_PROVIDER_EVIDENCE', 'UNDER_REVIEW', 'ESCALATED'
                )
                """, Integer.class, transactionId);
        return count != null && count > 0;
    }

    int unsatisfiedRequirements(UUID disputeId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from evidence_requirements
                where target_type = 'DISPUTE' and target_id = ? and satisfied = false
                """, Integer.class, disputeId);
        return count == null ? 0 : count;
    }

    Optional<TransactionDisputeView> transaction(UUID transactionId) {
        return jdbcTemplate.query("""
                select id, listing_id, requester_participant_id, provider_participant_id, status, transaction_type
                from marketplace_transactions where id = ?
                """, (rs, rowNum) -> new TransactionDisputeView(
                rs.getObject("id", UUID.class),
                rs.getObject("listing_id", UUID.class),
                rs.getObject("requester_participant_id", UUID.class),
                rs.getObject("provider_participant_id", UUID.class),
                rs.getString("status"),
                rs.getString("transaction_type")
        ), transactionId).stream().findFirst();
    }

    private DisputeResponse disputeRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        String outcome = rs.getString("outcome");
        return new DisputeResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("transaction_id", UUID.class),
                rs.getObject("opened_by_participant_id", UUID.class),
                DisputeType.valueOf(rs.getString("dispute_type")),
                DisputeStatus.valueOf(rs.getString("status")),
                outcome == null ? null : DisputeOutcome.valueOf(outcome),
                rs.getString("reason"),
                rs.getString("resolution_reason"),
                rs.getString("resolved_by"),
                rs.getTimestamp("opened_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                resolvedAt == null ? null : resolvedAt.toInstant(),
                readMap(rs.getString("metadata_json"))
        );
    }

    private DisputeStatementResponse statementRow(ResultSet rs, int rowNum) throws SQLException {
        return new DisputeStatementResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("dispute_id", UUID.class),
                rs.getObject("participant_id", UUID.class),
                StatementType.valueOf(rs.getString("statement_type")),
                rs.getString("statement_text"),
                rs.getString("actor"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
                readMap(rs.getString("metadata_json"))
        );
    }

    private DisputeEvidenceDeadlineResponse deadlineRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp satisfiedAt = rs.getTimestamp("satisfied_at");
        Timestamp missedAt = rs.getTimestamp("missed_at");
        return new DisputeEvidenceDeadlineResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("dispute_id", UUID.class),
                rs.getString("required_from_role"),
                rs.getTimestamp("due_at").toInstant(),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                satisfiedAt == null ? null : satisfiedAt.toInstant(),
                missedAt == null ? null : missedAt.toInstant()
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

    public record TransactionDisputeView(UUID transactionId, UUID listingId, UUID requesterParticipantId,
                                         UUID providerParticipantId, String status, String transactionType) {
    }
}
