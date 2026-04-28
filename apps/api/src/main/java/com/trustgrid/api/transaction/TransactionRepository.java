package com.trustgrid.api.transaction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trustgrid.api.category.ListingType;
import com.trustgrid.api.listing.ListingRiskTier;
import com.trustgrid.api.participant.ParticipantAccountStatus;
import com.trustgrid.api.participant.VerificationStatus;
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
public class TransactionRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TransactionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    Optional<ListingTransactionView> lockListing(UUID listingId) {
        return jdbcTemplate.query("""
                select l.id, l.owner_participant_id, l.listing_type, l.status, l.single_accept,
                       l.price_amount_cents, l.budget_amount_cents, l.currency, l.risk_tier,
                       coalesce(s.buyer_budget_cents + s.shopper_reward_cents, 0) as shopping_value
                from marketplace_listings l
                left join listing_shopping_request_details s on s.listing_id = l.id
                where l.id = ?
                for update of l
                """, this::listingViewRow, listingId).stream().findFirst();
    }

    Optional<ParticipantTransactionView> participant(UUID participantId) {
        return jdbcTemplate.query("""
                select p.id, p.account_status, p.verification_status, p.trust_tier,
                       coalesce(tp.max_transaction_value_cents, 0) as max_transaction_value_cents
                from participants p
                left join trust_profiles tp on tp.participant_id = p.id
                where p.id = ?
                """, (rs, rowNum) -> new ParticipantTransactionView(
                rs.getObject("id", UUID.class),
                ParticipantAccountStatus.valueOf(rs.getString("account_status")),
                VerificationStatus.valueOf(rs.getString("verification_status")),
                rs.getString("trust_tier"),
                rs.getLong("max_transaction_value_cents")
        ), participantId).stream().findFirst();
    }

    boolean hasCapability(UUID participantId, String capability) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from participant_capabilities
                where participant_id = ? and capability = ? and status = 'ACTIVE'
                """, Integer.class, participantId, capability);
        return count != null && count > 0;
    }

    boolean hasActiveRestriction(UUID participantId, String restrictionType) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from participant_restrictions
                where participant_id = ? and restriction_type = ? and status = 'ACTIVE'
                """, Integer.class, participantId, restrictionType);
        return count != null && count > 0;
    }

    boolean hasActiveTransaction(UUID listingId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from marketplace_transactions
                where listing_id = ? and status not in ('COMPLETED', 'CANCELLED', 'DISPUTED')
                """, Integer.class, listingId);
        return count != null && count > 0;
    }

    UUID insertTransaction(ListingTransactionView listing, TransactionType type, UUID requesterId, UUID providerId,
                           long value, String idempotencyKey, Map<String, Object> metadata, TransactionStatus status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into marketplace_transactions (
                    id, listing_id, transaction_type, requester_participant_id, provider_participant_id,
                    status, value_amount_cents, currency, risk_status, idempotency_key, accepted_at, metadata_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 'ALLOWED', ?, now(), cast(? as jsonb))
                """, id, listing.id(), type.name(), requesterId, providerId, status.name(), value, listing.currency(), idempotencyKey, json(metadata));
        return id;
    }

    Optional<TransactionResponse> find(UUID transactionId) {
        return jdbcTemplate.query("""
                select id, listing_id, transaction_type, requester_participant_id, provider_participant_id, status,
                       value_amount_cents, currency, risk_status, metadata_json, created_at, updated_at, completed_at, cancelled_at
                from marketplace_transactions
                where id = ?
                """, this::transactionRow, transactionId).stream().findFirst();
    }

    void updateStatus(UUID transactionId, TransactionStatus status, String timestampColumn) {
        jdbcTemplate.update("""
                update marketplace_transactions
                set status = ?, updated_at = now(), %s = now()
                where id = ?
                """.formatted(timestampColumn), status.name(), transactionId);
    }

    void cancelActiveDeadlines(UUID transactionId) {
        jdbcTemplate.update("""
                update transaction_deadlines
                set status = 'CANCELLED', cancelled_at = now()
                where transaction_id = ? and status = 'ACTIVE'
                """, transactionId);
    }

    void satisfyDeadline(UUID transactionId, String deadlineType) {
        jdbcTemplate.update("""
                update transaction_deadlines
                set status = 'SATISFIED', satisfied_at = now()
                where transaction_id = ? and deadline_type = ? and status = 'ACTIVE'
                """, transactionId, deadlineType);
    }

    void createDeadline(UUID transactionId, String deadlineType, int days) {
        jdbcTemplate.update("""
                insert into transaction_deadlines (id, transaction_id, deadline_type, due_at)
                values (?, ?, ?, now() + (? || ' days')::interval)
                """, UUID.randomUUID(), transactionId, deadlineType, days);
    }

    UUID insertRiskSnapshot(UUID transactionId, UUID listingId, UUID requesterId, UUID providerId, String decision, List<String> rules) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into transaction_risk_snapshots (
                    id, transaction_id, listing_id, requester_participant_id, provider_participant_id,
                    decision, matched_rules_json, snapshot_json
                ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb))
                """, id, transactionId, listingId, requesterId, providerId, decision, json(rules),
                json(Map.of("requesterParticipantId", requesterId, "providerParticipantId", providerId)));
        return id;
    }

    void timeline(UUID transactionId, String eventType, UUID actorParticipantId, String actor, String reason, Map<String, Object> payload) {
        jdbcTemplate.update("""
                insert into transaction_timeline_events (
                    id, transaction_id, event_type, actor_participant_id, actor, reason, payload_json
                ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """, UUID.randomUUID(), transactionId, eventType, actorParticipantId, actor, reason, json(payload));
    }

    List<TransactionTimelineEventResponse> timeline(UUID transactionId, String eventType, Instant from, Instant to, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select id, transaction_id, event_type, actor_participant_id, actor, reason, payload_json, created_at
                from transaction_timeline_events
                where transaction_id = ?
                """);
        args.add(transactionId);
        if (eventType != null && !eventType.isBlank()) {
            sql.append(" and event_type = ? ");
            args.add(eventType);
        }
        if (from != null) {
            sql.append(" and created_at >= ? ");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" and created_at <= ? ");
            args.add(Timestamp.from(to));
        }
        sql.append(" order by created_at asc limit ? offset ? ");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), this::timelineRow, args.toArray());
    }

    void insertEvent(UUID transactionId, UUID actorParticipantId, String eventType, Map<String, Object> payload) {
        jdbcTemplate.update("""
                insert into marketplace_events (
                    id, aggregate_type, aggregate_id, participant_id, event_key, event_type, payload_json
                ) values (?, 'TRANSACTION', ?, ?, ?, ?, cast(? as jsonb))
                """, UUID.randomUUID(), transactionId, actorParticipantId, eventType + ":" + transactionId + ":" + UUID.randomUUID(), eventType, json(payload));
    }

    void storeInvariant(UUID transactionId, String checkName, String status, String message) {
        jdbcTemplate.update("""
                insert into transaction_invariant_checks (id, transaction_id, check_name, status, message)
                values (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), transactionId, checkName, status, message);
    }

    int activeDeadlineCount(UUID transactionId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from transaction_deadlines
                where transaction_id = ? and status = 'ACTIVE'
                """, Integer.class, transactionId);
        return count == null ? 0 : count;
    }

    int timelineCount(UUID transactionId, String eventType) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from transaction_timeline_events
                where transaction_id = ? and event_type = ?
                """, Integer.class, transactionId, eventType);
        return count == null ? 0 : count;
    }

    int marketplaceEventCount(UUID transactionId, String eventType) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from marketplace_events
                where aggregate_type = 'TRANSACTION' and aggregate_id = ? and event_type = ?
                """, Integer.class, transactionId, eventType);
        return count == null ? 0 : count;
    }

    int riskSnapshotCount(UUID transactionId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from transaction_risk_snapshots
                where transaction_id = ?
                """, Integer.class, transactionId);
        return count == null ? 0 : count;
    }

    boolean listingExists(UUID listingId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from marketplace_listings where id = ?
                """, Integer.class, listingId);
        return count != null && count > 0;
    }

    Optional<ListingTransactionState> listingState(UUID listingId) {
        return jdbcTemplate.query("""
                select id, status, single_accept from marketplace_listings where id = ?
                """, (rs, rowNum) -> new ListingTransactionState(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getBoolean("single_accept")
        ), listingId).stream().findFirst();
    }

    int activeTransactionsForSingleAcceptListing(UUID listingId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from marketplace_transactions t
                join marketplace_listings l on l.id = t.listing_id
                where t.listing_id = ?
                  and l.single_accept = true
                  and t.status not in ('COMPLETED', 'CANCELLED', 'DISPUTED', 'NO_SHOW_REPORTED')
                """, Integer.class, listingId);
        return count == null ? 0 : count;
    }

    private ListingTransactionView listingViewRow(ResultSet rs, int rowNum) throws SQLException {
        return new ListingTransactionView(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_participant_id", UUID.class),
                ListingType.valueOf(rs.getString("listing_type")),
                rs.getString("status"),
                rs.getBoolean("single_accept"),
                (Long) rs.getObject("price_amount_cents"),
                (Long) rs.getObject("budget_amount_cents"),
                rs.getString("currency"),
                ListingRiskTier.valueOf(rs.getString("risk_tier")),
                rs.getLong("shopping_value")
        );
    }

    private TransactionResponse transactionRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
        return new TransactionResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("listing_id", UUID.class),
                TransactionType.valueOf(rs.getString("transaction_type")),
                rs.getObject("requester_participant_id", UUID.class),
                rs.getObject("provider_participant_id", UUID.class),
                TransactionStatus.valueOf(rs.getString("status")),
                rs.getLong("value_amount_cents"),
                rs.getString("currency"),
                TransactionRiskStatus.valueOf(rs.getString("risk_status")),
                readMap(rs.getString("metadata_json")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant(),
                cancelledAt == null ? null : cancelledAt.toInstant()
        );
    }

    private TransactionTimelineEventResponse timelineRow(ResultSet rs, int rowNum) throws SQLException {
        return new TransactionTimelineEventResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("transaction_id", UUID.class),
                rs.getString("event_type"),
                rs.getObject("actor_participant_id", UUID.class),
                rs.getString("actor"),
                rs.getString("reason"),
                readMap(rs.getString("payload_json")),
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

    Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    record ListingTransactionView(
            UUID id,
            UUID ownerParticipantId,
            ListingType listingType,
            String status,
            boolean singleAccept,
            Long priceAmountCents,
            Long budgetAmountCents,
            String currency,
            ListingRiskTier riskTier,
            long shoppingValue
    ) {
    }

    record ParticipantTransactionView(
            UUID participantId,
            ParticipantAccountStatus accountStatus,
            VerificationStatus verificationStatus,
            String trustTier,
            long maxTransactionValueCents
    ) {
    }

    record ListingTransactionState(UUID listingId, String status, boolean singleAccept) {
    }
}
