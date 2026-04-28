package com.trustgrid.api.ops;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OpsQueueRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OpsQueueRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    UUID insert(CreateOpsQueueItemRequest request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into marketplace_ops_queue_items (
                    id, queue_type, target_type, target_id, priority, reason, signals_json
                ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb))
                on conflict do nothing
                """, id, request.queueType(), request.targetType(), request.targetId(), request.priority(),
                request.reason(), json(request.signals() == null ? List.of() : request.signals()));
        return id;
    }

    int rebuild() {
        int count = 0;
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select id from marketplace_listings where risk_tier in ('HIGH', 'RESTRICTED') or status in ('UNDER_REVIEW')
                """)) {
            insert(new CreateOpsQueueItemRequest("HIGH_RISK_LISTINGS", "LISTING", (UUID) row.get("id"), "HIGH",
                    "High-risk or reviewed listing", List.of("listing_risk")));
            count++;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select id from marketplace_disputes where status in ('OPEN', 'UNDER_REVIEW', 'ESCALATED')
                """)) {
            insert(new CreateOpsQueueItemRequest("OPEN_DISPUTES", "DISPUTE", (UUID) row.get("id"), "HIGH",
                    "Open dispute requires review", List.of("open_dispute")));
            count++;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList("select id, severity from review_abuse_clusters where status = 'OPEN'")) {
            insert(new CreateOpsQueueItemRequest("FAKE_REVIEW_CLUSTERS", "REVIEW_ABUSE_CLUSTER", (UUID) row.get("id"),
                    row.get("severity").toString(), "Review abuse cluster detected", List.of("review_abuse_cluster")));
            count++;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList("select target_type, target_id from evidence_requirements where satisfied = false")) {
            insert(new CreateOpsQueueItemRequest("EVIDENCE_MISSING", row.get("target_type").toString(), (UUID) row.get("target_id"),
                    "MEDIUM", "Evidence requirement is unsatisfied", List.of("evidence_missing")));
            count++;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select target_id from risk_decisions where target_type = 'PARTICIPANT' and risk_level in ('HIGH', 'CRITICAL')
                """)) {
            insert(new CreateOpsQueueItemRequest("SUSPICIOUS_ACCOUNTS", "PARTICIPANT", (UUID) row.get("target_id"),
                    "HIGH", "High-risk decision for participant", List.of("participant_risk")));
            count++;
        }
        return count;
    }

    List<OpsQueueItemResponse> search(String queueType, String status) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("select * from marketplace_ops_queue_items where 1=1 ");
        if (queueType != null && !queueType.isBlank()) {
            sql.append(" and queue_type = ? ");
            args.add(queueType);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and status = ? ");
            args.add(status);
        }
        sql.append(" order by created_at desc ");
        return jdbcTemplate.query(sql.toString(), this::row, args.toArray());
    }

    OpsQueueItemResponse get(UUID id) {
        return jdbcTemplate.queryForObject("select * from marketplace_ops_queue_items where id = ?", this::row, id);
    }

    void updateStatus(UUID id, String status) {
        jdbcTemplate.update("""
                update marketplace_ops_queue_items
                set status = ?, updated_at = now(), resolved_at = case when ? in ('RESOLVED', 'CANCELLED') then now() else resolved_at end
                where id = ?
                """, status, status, id);
    }

    UUID moderatorAction(String actionType, String targetType, UUID targetId, String actor, String reason,
                         Map<String, Object> before, Map<String, Object> after) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into moderator_actions (id, action_type, target_type, target_id, actor, reason, before_json, after_json)
                values (?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb))
                """, id, actionType, targetType, targetId, actor, reason, json(before), json(after));
        return id;
    }

    List<Map<String, Object>> moderatorActions() {
        return jdbcTemplate.queryForList("select * from moderator_actions order by created_at desc");
    }

    Map<String, Object> listingState(UUID id) {
        return jdbcTemplate.queryForMap("select id, status, moderation_status from marketplace_listings where id = ?", id);
    }

    Map<String, Object> capabilityState(UUID participantId, String capability) {
        return jdbcTemplate.queryForList("""
                select id, participant_id, capability, status from participant_capabilities
                where participant_id = ? and capability = ?
                """, participantId, capability).stream().findFirst()
                .orElse(Map.of("participant_id", participantId, "capability", capability, "status", "MISSING"));
    }

    Map<String, Object> reviewState(UUID reviewId) {
        return jdbcTemplate.queryForMap("select id, status, confidence_weight, suppression_reason from marketplace_reviews where id = ?", reviewId);
    }

    Map<String, Object> disputeState(UUID disputeId) {
        return jdbcTemplate.queryForMap("select id, status from marketplace_disputes where id = ?", disputeId);
    }

    int evidenceRequirementCount(String targetType, UUID targetId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from evidence_requirements where target_type = ? and target_id = ?
                """, Integer.class, targetType, targetId);
        return count == null ? 0 : count;
    }

    UUID manualCase(String targetType, UUID targetId, String openedBy, String reason, UUID queueItemId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into manual_review_cases (id, queue_item_id, target_type, target_id, opened_by, reason)
                values (?, ?, ?, ?, ?, ?)
                """, id, queueItemId, targetType, targetId, openedBy, reason);
        return id;
    }

    UUID safetyEscalation(String targetType, UUID targetId, String severity, String actor, String reason) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into safety_escalations (id, target_type, target_id, severity, actor, reason)
                values (?, ?, ?, ?, ?, ?)
                """, id, targetType, targetId, severity, actor, reason);
        return id;
    }

    void status(String table, UUID id, String status) {
        if ("safety_escalations".equals(table)) {
            jdbcTemplate.update("update safety_escalations set status = ?, resolved_at = case when ? in ('RESOLVED','FALSE_POSITIVE','MITIGATED') then now() else resolved_at end where id = ?",
                    status, status, id);
            return;
        }
        jdbcTemplate.update("update " + table + " set status = ?, updated_at = now(), resolved_at = case when ? in ('RESOLVED','CANCELLED','FALSE_POSITIVE','MITIGATED') then now() else resolved_at end where id = ?",
                status, status, id);
    }

    List<Map<String, Object>> rows(String table) {
        return jdbcTemplate.queryForList("select * from " + table + " order by created_at desc");
    }

    void hideListing(UUID id) {
        jdbcTemplate.update("update marketplace_listings set status = 'HIDDEN', moderation_status = 'MODERATOR_HIDDEN', hidden_at = now() where id = ?", id);
        jdbcTemplate.update("update listing_search_documents set searchable = false, status = 'HIDDEN' where listing_id = ?", id);
    }

    UUID requestEvidence(String targetType, UUID targetId, String evidenceType, String reason) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into evidence_requirements (id, target_type, target_id, evidence_type, required_before_action, reason)
                values (?, ?, ?, ?, 'MANUAL_REVIEW', ?)
                """, id, targetType, targetId, evidenceType, reason);
        return id;
    }

    void suppressReview(UUID reviewId, String reason) {
        jdbcTemplate.update("update marketplace_reviews set status = 'SUPPRESSED', confidence_weight = 0, suppression_reason = ? where id = ?",
                reason, reviewId);
    }

    void restoreReview(UUID reviewId, String reason) {
        jdbcTemplate.update("""
                update marketplace_reviews
                set status = 'ACTIVE',
                    confidence_weight = greatest(confidence_weight, 10),
                    suppression_reason = ?
                where id = ?
                """, "Restored: " + reason, reviewId);
    }

    void restrictCapability(UUID participantId, String capability, String actor, String reason) {
        jdbcTemplate.update("""
                insert into participant_capabilities (id, participant_id, capability, status, restricted_by, restrict_reason)
                values (?, ?, ?, 'RESTRICTED', ?, ?)
                on conflict (participant_id, capability) do update
                set status = 'RESTRICTED', restricted_by = excluded.restricted_by,
                    restrict_reason = excluded.restrict_reason, updated_at = now()
                """, UUID.randomUUID(), participantId, capability, actor, reason);
    }

    void restoreCapability(UUID participantId, String capability, String actor, String reason) {
        jdbcTemplate.update("""
                update participant_capabilities
                set status = 'ACTIVE', granted_by = ?, grant_reason = ?, restricted_by = null,
                    restrict_reason = null, updated_at = now()
                where participant_id = ? and capability = ?
                """, actor, reason, participantId, capability);
    }

    UUID requestVerification(UUID participantId, String actor, String reason) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into participant_restrictions (id, participant_id, restriction_type, status, actor, reason, metadata_json)
                values (?, ?, 'REQUIRES_VERIFICATION', 'ACTIVE', ?, ?, '{"source":"moderator_action"}'::jsonb)
                """, id, participantId, actor, reason);
        return id;
    }

    void escalateDispute(UUID disputeId) {
        jdbcTemplate.update("update marketplace_disputes set status = 'ESCALATED', updated_at = now() where id = ?", disputeId);
    }

    private OpsQueueItemResponse row(ResultSet rs, int rowNum) throws SQLException {
        return new OpsQueueItemResponse(rs.getObject("id", UUID.class), rs.getString("queue_type"),
                rs.getString("target_type"), rs.getObject("target_id", UUID.class), rs.getString("priority"),
                rs.getString("status"), rs.getString("reason"), readStrings(rs.getString("signals_json")),
                rs.getString("assigned_to"), rs.getTimestamp("created_at").toInstant());
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }

    private List<String> readStrings(String value) {
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (Exception exception) {
            return List.of();
        }
    }
}
