package com.trustgrid.api.paymentboundary;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentBoundaryRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PaymentBoundaryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    String transactionStatus(UUID transactionId) {
        return jdbcTemplate.queryForObject("select status from marketplace_transactions where id = ?", String.class, transactionId);
    }

    boolean resolvedDisputeExists(UUID transactionId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from marketplace_disputes
                where transaction_id = ? and status in ('RESOLVED_BUYER', 'RESOLVED_SELLER', 'RESOLVED_PROVIDER', 'SPLIT_DECISION', 'ESCALATED', 'CLOSED')
                """, Integer.class, transactionId);
        return count != null && count > 0;
    }

    boolean unresolvedDisputeExists(UUID transactionId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from marketplace_disputes
                where transaction_id = ? and status in (
                    'OPEN', 'AWAITING_BUYER_EVIDENCE', 'AWAITING_SELLER_EVIDENCE',
                    'AWAITING_PROVIDER_EVIDENCE', 'UNDER_REVIEW', 'ESCALATED'
                )
                """, Integer.class, transactionId);
        return count != null && count > 0;
    }

    Optional<Map<String, Object>> latestResolvedDispute(UUID transactionId) {
        return jdbcTemplate.queryForList("""
                select id, status, outcome, resolution_reason
                from marketplace_disputes
                where transaction_id = ?
                  and status in ('RESOLVED_BUYER', 'RESOLVED_SELLER', 'RESOLVED_PROVIDER', 'SPLIT_DECISION', 'ESCALATED', 'CLOSED')
                order by resolved_at desc nulls last, updated_at desc
                limit 1
                """, transactionId).stream().findFirst();
    }

    boolean hasPayoutHoldRiskEvidence(UUID transactionId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from marketplace_transactions t
                join participants p on p.id = t.provider_participant_id
                where t.id = ?
                  and (
                    p.risk_level in ('HIGH', 'CRITICAL')
                    or exists (
                        select 1 from participant_restrictions r
                        where r.participant_id = p.id and r.status = 'ACTIVE'
                          and r.restriction_type in ('ACCEPTING_BLOCKED', 'LISTING_BLOCKED', 'REQUIRES_MANUAL_REVIEW', 'REVIEW_WEIGHT_SUPPRESSED')
                    )
                    or exists (
                        select 1 from risk_decisions rd
                        where ((rd.target_type = 'TRANSACTION' and rd.target_id = t.id)
                            or (rd.target_type = 'PARTICIPANT' and rd.target_id = p.id))
                          and (rd.risk_level in ('HIGH', 'CRITICAL')
                            or rd.decision in ('REQUIRE_MANUAL_REVIEW', 'RESTRICT_CAPABILITY', 'SUSPEND_ACCOUNT'))
                    )
                    or exists (
                        select 1 from safety_escalations se
                        where se.status in ('OPEN', 'INVESTIGATING')
                          and ((se.target_type = 'TRANSACTION' and se.target_id = t.id)
                            or (se.target_type = 'PARTICIPANT' and se.target_id = p.id))
                    )
                    or exists (
                        select 1 from marketplace_disputes d
                        where d.transaction_id = t.id and d.outcome = 'FRAUD_SUSPECTED'
                    )
                  )
                """, Integer.class, transactionId);
        return count != null && count > 0;
    }

    boolean hasPriorBoundaryDecision(UUID transactionId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from payment_boundary_states
                where transaction_id = ?
                  and state in ('RELEASE_REQUESTED', 'REFUND_REQUESTED', 'PAYOUT_HOLD_REQUESTED')
                """, Integer.class, transactionId);
        return count != null && count > 0;
    }

    Optional<PaymentBoundaryEventResponse> existingEvent(UUID transactionId, String eventType) {
        return jdbcTemplate.query("""
                select * from payment_boundary_events
                where transaction_id = ? and event_type = ?
                order by created_at desc limit 1
                """, this::eventRow, transactionId, eventType).stream().findFirst();
    }

    Optional<UUID> latestState(UUID transactionId, String state) {
        return jdbcTemplate.query("""
                select id from payment_boundary_states
                where transaction_id = ? and state = ?
                order by created_at desc limit 1
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), transactionId, state).stream().findFirst();
    }

    UUID state(UUID transactionId, String state, String reason, String createdBy, Map<String, Object> metadata) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into payment_boundary_states (id, transaction_id, state, reason, created_by, metadata_json)
                values (?, ?, ?, ?, ?, cast(? as jsonb))
                """, id, transactionId, state, reason, createdBy, json(metadata));
        return id;
    }

    UUID event(UUID transactionId, String eventType, String reason, String requestedBy, Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into payment_boundary_events (id, transaction_id, event_type, event_key, reason, requested_by, payload_json)
                values (?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """, id, transactionId, eventType, eventType + ":" + transactionId, reason, requestedBy, json(payload));
        return id;
    }

    List<PaymentBoundaryStateResponse> states(UUID transactionId) {
        return jdbcTemplate.query("select * from payment_boundary_states where transaction_id = ? order by created_at desc",
                this::stateRow, transactionId);
    }

    List<PaymentBoundaryEventResponse> events(UUID transactionId) {
        return jdbcTemplate.query("select * from payment_boundary_events where transaction_id = ? order by created_at desc",
                this::eventRow, transactionId);
    }

    private PaymentBoundaryStateResponse stateRow(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentBoundaryStateResponse(rs.getObject("id", UUID.class), rs.getObject("transaction_id", UUID.class),
                rs.getString("state"), rs.getString("reason"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), readMap(rs.getString("metadata_json")));
    }

    private PaymentBoundaryEventResponse eventRow(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentBoundaryEventResponse(rs.getObject("id", UUID.class), rs.getObject("transaction_id", UUID.class),
                rs.getString("event_type"), rs.getString("event_key"), rs.getString("reason"), rs.getString("requested_by"),
                readMap(rs.getString("payload_json")), rs.getTimestamp("created_at").toInstant());
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
