package com.trustgrid.api.repair;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trustgrid.api.shared.ConflictException;
import com.trustgrid.api.shared.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DataRepairRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DataRepairRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    int generate() {
        return jdbcTemplate.update("""
                insert into data_repair_recommendations (
                    id, consistency_finding_id, repair_type, target_type, target_id, severity, recommendation_json, reason
                )
                select gen_random_uuid(), f.id,
                       case
                         when f.finding_type in ('REPUTATION_DRIFT','TRUST_PROFILE') then 'REBUILD_REPUTATION'
                         when f.finding_type = 'SEARCH_INDEX_DRIFT' then 'REBUILD_SEARCH_INDEX'
                         when f.finding_type = 'EVIDENCE_REFERENCE_INVALID' then 'MARK_EVIDENCE_REFERENCE_INVALID'
                         when f.finding_type = 'ANALYTICS_MISSING_EVENT' then 'REPLAY_EVENTS'
                         when f.finding_type = 'EXPIRED_TEMPORARY_GRANT_STILL_ACTIVE' then 'EXPIRE_TEMPORARY_CAPABILITY_GRANT'
                         when f.finding_type = 'EXPIRED_BREAK_GLASS_STILL_ACTIVE' then 'EXPIRE_BREAK_GLASS_CAPABILITY_ACTION'
                         when f.finding_type = 'CAPABILITY_DECISION_REPLAY_MISMATCH' then 'REQUEST_CAPABILITY_DECISION_REVIEW'
                         when f.finding_type = 'ACTIVE_GRANT_FOR_CLOSED_PARTICIPANT' then 'REVOKE_GRANT_FOR_CLOSED_PARTICIPANT'
                         when f.finding_type = 'ACTIVE_BREAK_GLASS_FOR_CLOSED_PARTICIPANT' then 'REVOKE_BREAK_GLASS_FOR_CLOSED_PARTICIPANT'
                         when f.finding_type like 'TRUST_CASE_%' then 'REQUEST_TRUST_CASE_TARGET_REVIEW'
                         when f.finding_type like 'CAMPAIGN_%' then 'REQUEST_CAMPAIGN_GRAPH_REBUILD'
                         when f.finding_type like 'EVIDENCE_%' then 'REQUEST_EVIDENCE_CUSTODY_REVIEW'
                         when f.finding_type like 'GUARANTEE_%' then 'REQUEST_GUARANTEE_MANUAL_REVIEW'
                         when f.finding_type like 'ENFORCEMENT_%' then 'REQUEST_ENFORCEMENT_QA_REVIEW'
                         when f.finding_type like 'RECOVERY_%' then 'REQUEST_RECOVERY_REVIEW'
                         when f.finding_type like 'ADVERSARIAL_%' then 'REQUEST_ADVERSARIAL_COVERAGE_REVIEW'
                         when f.finding_type like 'TRUST_DOSSIER_%' then 'REQUEST_TRUST_DOSSIER_REBUILD'
                         else 'REQUEST_OPERATOR_REVIEW'
                       end,
                       f.target_type, f.target_id, f.severity,
                       jsonb_build_object('findingType', f.finding_type, 'message', f.message, 'autoFix', false),
                       'Recommendation generated from consistency finding'
                from consistency_findings f
                where f.status = 'OPEN'
                  and not exists (
                    select 1 from data_repair_recommendations r
                    where r.consistency_finding_id = f.id and r.status in ('PROPOSED','APPROVED','APPLIED')
                  )
                """);
    }

    List<Map<String, Object>> list() {
        return jdbcTemplate.queryForList("select * from data_repair_recommendations order by created_at desc");
    }

    Map<String, Object> get(UUID id) {
        return jdbcTemplate.queryForList("select * from data_repair_recommendations where id = ?", id).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Data repair recommendation not found"));
    }

    void approve(UUID id, Map<String, Object> request) {
        require(request, "actor");
        require(request, "reason");
        int updated = jdbcTemplate.update("""
                update data_repair_recommendations set status = 'APPROVED', decided_at = now()
                where id = ? and status = 'PROPOSED'
                """, id);
        if (updated == 0) {
            ensureExists(id);
            throw new ConflictException("Data repair recommendation cannot be approved from current state");
        }
    }

    UUID apply(UUID id, Map<String, Object> request) {
        Map<String, Object> before = get(id);
        require(request, "actor");
        require(request, "reason");
        require(request, "riskAcknowledgement");
        String status = before.get("status").toString();
        if (!List.of("PROPOSED", "APPROVED").contains(status)) {
            throw new ConflictException("Data repair recommendation cannot be applied from current state");
        }
        Map<String, Object> repairResult = executeRepair(before, request);
        int updated = jdbcTemplate.update("""
                update data_repair_recommendations set status = 'APPLIED', decided_at = now()
                where id = ? and status in ('PROPOSED','APPROVED')
                """, id);
        if (updated == 0) {
            throw new ConflictException("Data repair recommendation cannot be applied from current state");
        }
        UUID findingId = (UUID) before.get("consistency_finding_id");
        if (findingId != null) {
            jdbcTemplate.update("update consistency_findings set status = 'RESOLVED', resolved_at = now() where id = ?", findingId);
        }
        UUID actionId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into operator_data_repair_actions (
                    id, repair_recommendation_id, action_type, target_type, target_id, actor, reason,
                    risk_acknowledgement, before_json, after_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb))
                """, actionId, id, actionType(before.get("repair_type").toString()), before.get("target_type"),
                before.get("target_id"), require(request, "actor"), require(request, "reason"),
                require(request, "riskAcknowledgement"), json(before),
                json(Map.of("recommendation", get(id), "repairResult", repairResult)));
        return actionId;
    }

    UUID reject(UUID id, Map<String, Object> request) {
        Map<String, Object> before = get(id);
        require(request, "actor");
        require(request, "reason");
        int updated = jdbcTemplate.update("""
                update data_repair_recommendations set status = 'REJECTED', decided_at = now()
                where id = ? and status in ('PROPOSED','APPROVED')
                """, id);
        if (updated == 0) {
            throw new ConflictException("Data repair recommendation cannot be rejected from current state");
        }
        UUID actionId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into operator_data_repair_actions (
                    id, repair_recommendation_id, action_type, target_type, target_id, actor, reason,
                    risk_acknowledgement, before_json, after_json
                ) values (?, ?, 'REJECT_RECOMMENDATION', ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb))
                """, actionId, id, before.get("target_type"), before.get("target_id"), require(request, "actor"),
                require(request, "reason"), request.getOrDefault("riskAcknowledgement", "Repair rejected"),
                json(before), json(get(id)));
        return actionId;
    }

    List<Map<String, Object>> actions() {
        return jdbcTemplate.queryForList("select * from operator_data_repair_actions order by created_at desc");
    }

    private String actionType(String repairType) {
        return switch (repairType) {
            case "REBUILD_REPUTATION" -> "APPLY_REBUILD_REPUTATION";
            case "REBUILD_SEARCH_INDEX" -> "APPLY_REBUILD_SEARCH_INDEX";
            case "REBUILD_LINEAGE" -> "APPLY_REBUILD_LINEAGE";
            case "MANUAL_REPAIR_REQUIRED", "REQUEST_OPERATOR_REVIEW" -> "RECORD_MANUAL_REPAIR";
            case "EXPIRE_TEMPORARY_CAPABILITY_GRANT" -> "EXPIRE_TEMPORARY_CAPABILITY_GRANT";
            case "EXPIRE_BREAK_GLASS_CAPABILITY_ACTION" -> "EXPIRE_BREAK_GLASS_CAPABILITY_ACTION";
            case "REQUEST_CAPABILITY_DECISION_REVIEW" -> "REQUEST_CAPABILITY_DECISION_REVIEW";
            case "REVOKE_GRANT_FOR_CLOSED_PARTICIPANT" -> "REVOKE_GRANT_FOR_CLOSED_PARTICIPANT";
            case "REVOKE_BREAK_GLASS_FOR_CLOSED_PARTICIPANT" -> "REVOKE_BREAK_GLASS_FOR_CLOSED_PARTICIPANT";
            case "REQUEST_TRUST_CASE_TARGET_REVIEW" -> "REQUEST_TRUST_CASE_TARGET_REVIEW";
            case "REQUEST_CAMPAIGN_GRAPH_REBUILD" -> "REQUEST_CAMPAIGN_GRAPH_REBUILD";
            case "REQUEST_EVIDENCE_CUSTODY_REVIEW" -> "REQUEST_EVIDENCE_CUSTODY_REVIEW";
            case "REQUEST_GUARANTEE_MANUAL_REVIEW" -> "REQUEST_GUARANTEE_MANUAL_REVIEW";
            case "REQUEST_ENFORCEMENT_QA_REVIEW" -> "REQUEST_ENFORCEMENT_QA_REVIEW";
            case "REQUEST_RECOVERY_REVIEW" -> "REQUEST_RECOVERY_REVIEW";
            case "REQUEST_ADVERSARIAL_COVERAGE_REVIEW" -> "REQUEST_ADVERSARIAL_COVERAGE_REVIEW";
            case "REQUEST_TRUST_DOSSIER_REBUILD" -> "REQUEST_TRUST_DOSSIER_REBUILD";
            default -> "MARK_FINDING_RESOLVED";
        };
    }

    private Map<String, Object> executeRepair(Map<String, Object> recommendation, Map<String, Object> request) {
        String repairType = recommendation.get("repair_type").toString();
        UUID targetId = (UUID) recommendation.get("target_id");
        return switch (repairType) {
            case "REBUILD_REPUTATION" -> rebuildReputation(targetId);
            case "REBUILD_SEARCH_INDEX" -> rebuildSearchIndex(targetId);
            case "REBUILD_LINEAGE" -> rebuildLineage(request);
            case "REPLAY_EVENTS" -> replayEvents();
            case "MARK_EVIDENCE_REFERENCE_INVALID" -> markEvidenceReferenceInvalid(targetId);
            case "REQUEST_OPERATOR_REVIEW" -> requestOperatorReview(recommendation, request);
            case "EXPIRE_TEMPORARY_CAPABILITY_GRANT" -> expireTemporaryGrant(targetId);
            case "EXPIRE_BREAK_GLASS_CAPABILITY_ACTION" -> expireBreakGlass(targetId);
            case "REQUEST_CAPABILITY_DECISION_REVIEW" -> requestOperatorReview(recommendation, request);
            case "REVOKE_GRANT_FOR_CLOSED_PARTICIPANT" -> revokeGrantForClosedParticipant(targetId, request);
            case "REVOKE_BREAK_GLASS_FOR_CLOSED_PARTICIPANT" -> revokeBreakGlassForClosedParticipant(targetId, request);
            case "REQUEST_TRUST_CASE_TARGET_REVIEW", "REQUEST_CAMPAIGN_GRAPH_REBUILD",
                    "REQUEST_EVIDENCE_CUSTODY_REVIEW", "REQUEST_GUARANTEE_MANUAL_REVIEW",
                    "REQUEST_ENFORCEMENT_QA_REVIEW", "REQUEST_RECOVERY_REVIEW",
                    "REQUEST_ADVERSARIAL_COVERAGE_REVIEW", "REQUEST_TRUST_DOSSIER_REBUILD" ->
                    requestOperatorReview(recommendation, request);
            case "MANUAL_REPAIR_REQUIRED" -> Map.of("repairExecuted", "MANUAL_REPAIR_RECORDED", "autoRepair", false);
            default -> Map.of("repairExecuted", "MARK_FINDING_RESOLVED", "autoRepair", false);
        };
    }

    private Map<String, Object> rebuildReputation(UUID participantId) {
        int updated;
        if (participantId == null) {
            updated = jdbcTemplate.update("""
                    update trust_profiles tp
                    set trust_score = latest.trust_score,
                        trust_confidence = latest.trust_confidence,
                        trust_tier = latest.trust_tier,
                        updated_at = now()
                    from (
                        select distinct on (participant_id) participant_id, trust_score, trust_confidence, trust_tier
                        from reputation_snapshots
                        order by participant_id, created_at desc
                    ) latest
                    where latest.participant_id = tp.participant_id
                    """);
        } else {
            updated = jdbcTemplate.update("""
                    update trust_profiles tp
                    set trust_score = latest.trust_score,
                        trust_confidence = latest.trust_confidence,
                        trust_tier = latest.trust_tier,
                        updated_at = now()
                    from (
                        select participant_id, trust_score, trust_confidence, trust_tier
                        from reputation_snapshots
                        where participant_id = ?
                        order by created_at desc
                        limit 1
                    ) latest
                    where latest.participant_id = tp.participant_id
                    """, participantId);
        }
        return Map.of("repairExecuted", "REBUILD_REPUTATION", "trustProfilesUpdated", updated);
    }

    private Map<String, Object> rebuildSearchIndex(UUID listingId) {
        int changed;
        if (listingId == null) {
            jdbcTemplate.update("delete from listing_search_documents");
            changed = insertSearchDocuments(null);
        } else {
            jdbcTemplate.update("delete from listing_search_documents where listing_id = ?", listingId);
            changed = insertSearchDocuments(listingId);
        }
        return Map.of("repairExecuted", "REBUILD_SEARCH_INDEX", "documentsRebuilt", changed);
    }

    private int insertSearchDocuments(UUID listingId) {
        String filter = listingId == null ? "" : " where l.id = ? ";
        Object[] args = listingId == null ? new Object[]{} : new Object[]{listingId};
        return jdbcTemplate.update("""
                insert into listing_search_documents (
                    listing_id, owner_participant_id, listing_type, category_code, title, description,
                    price_amount_cents, budget_amount_cents, location_mode, status, risk_tier, searchable,
                    search_backend_status, indexed_at, document_json
                )
                select l.id, l.owner_participant_id, l.listing_type, c.code, l.title, l.description,
                       l.price_amount_cents, l.budget_amount_cents, l.location_mode, l.status, l.risk_tier,
                       l.status = 'LIVE' and p.account_status not in ('SUSPENDED', 'CLOSED', 'RESTRICTED')
                         and not exists (
                           select 1 from participant_restrictions r
                           where r.participant_id = p.id and r.status = 'ACTIVE'
                             and r.restriction_type in ('HIDDEN_FROM_MARKETPLACE_SEARCH', 'LISTING_BLOCKED')
                         ),
                       'POSTGRES_FALLBACK', now(), jsonb_build_object('rebuiltBy', 'operator_data_repair')
                from marketplace_listings l
                join marketplace_categories c on c.id = l.category_id
                join participants p on p.id = l.owner_participant_id
                """ + filter, args);
    }

    private Map<String, Object> rebuildLineage(Map<String, Object> request) {
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into lineage_rebuild_runs (id, rebuild_type, status, requested_by, reason, completed_at, summary_json)
                values (?, 'FULL_LINEAGE', 'SUCCEEDED', ?, ?, now(), cast(? as jsonb))
                """, runId, require(request, "actor"), require(request, "reason"),
                json(Map.of("triggeredBy", "operator_data_repair", "repairExecuted", true)));
        return Map.of("repairExecuted", "REBUILD_LINEAGE", "lineageRebuildRunId", runId);
    }

    private Map<String, Object> replayEvents() {
        int inserted = jdbcTemplate.update("""
                insert into marketplace_event_analytics (
                    id, source_event_id, aggregate_type, aggregate_id, event_type, occurred_at, payload_json
                )
                select gen_random_uuid(), e.id, e.aggregate_type, e.aggregate_id, e.event_type, e.created_at, e.payload_json
                from marketplace_events e
                where not exists (
                    select 1 from marketplace_event_analytics a where a.source_event_id = e.id
                )
                on conflict do nothing
                """);
        return Map.of("repairExecuted", "REPLAY_EVENTS", "analyticsEventsInserted", inserted);
    }

    private Map<String, Object> markEvidenceReferenceInvalid(UUID targetId) {
        int exists = targetId == null ? 0 : count("""
                select count(*) from evidence_requirements er
                left join marketplace_evidence e on e.id = er.satisfied_by_evidence_id
                where er.id = ? and er.satisfied = true and e.id is null
                """, targetId);
        return Map.of("repairExecuted", "MARK_EVIDENCE_REFERENCE_INVALID", "invalidReferenceStillPresent", exists > 0);
    }

    private Map<String, Object> requestOperatorReview(Map<String, Object> recommendation, Map<String, Object> request) {
        UUID queueId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into marketplace_ops_queue_items (
                    id, queue_type, target_type, target_id, priority, status, reason, signals_json
                ) values (?, 'CONSISTENCY_REVIEW', ?, ?, 'MEDIUM', 'OPEN', ?, cast(? as jsonb))
                """, queueId, recommendation.get("target_type"), recommendation.get("target_id"),
                require(request, "reason"), json(Map.of("repairRecommendationId", recommendation.get("id"))));
        return Map.of("repairExecuted", "REQUEST_OPERATOR_REVIEW", "queueItemId", queueId);
    }

    private Map<String, Object> expireTemporaryGrant(UUID targetId) {
        int updated = jdbcTemplate.update("""
                update temporary_capability_grants
                set status = 'EXPIRED'
                where id = ? and status = 'ACTIVE' and expires_at <= now()
                """, targetId);
        if (updated == 0) {
            throw new ConflictException("Temporary capability grant was not eligible for expiry repair");
        }
        return Map.of("repairExecuted", "EXPIRE_TEMPORARY_CAPABILITY_GRANT", "rowsUpdated", updated);
    }

    private Map<String, Object> expireBreakGlass(UUID targetId) {
        int updated = jdbcTemplate.update("""
                update break_glass_capability_actions
                set status = 'EXPIRED'
                where id = ? and status = 'ACTIVE' and expires_at <= now()
                """, targetId);
        if (updated == 0) {
            throw new ConflictException("Break-glass capability action was not eligible for expiry repair");
        }
        return Map.of("repairExecuted", "EXPIRE_BREAK_GLASS_CAPABILITY_ACTION", "rowsUpdated", updated);
    }

    private Map<String, Object> revokeGrantForClosedParticipant(UUID targetId, Map<String, Object> request) {
        int updated = jdbcTemplate.update("""
                update temporary_capability_grants g
                set status = 'REVOKED', revoked_at = now(), revoked_by = ?, revoke_reason = ?
                from participants p
                where g.id = ? and g.participant_id = p.id and p.account_status = 'CLOSED' and g.status = 'ACTIVE'
                """, require(request, "actor"), require(request, "reason"), targetId);
        if (updated == 0) {
            throw new ConflictException("Temporary capability grant was not eligible for closed-account revoke repair");
        }
        return Map.of("repairExecuted", "REVOKE_GRANT_FOR_CLOSED_PARTICIPANT", "rowsUpdated", updated);
    }

    private Map<String, Object> revokeBreakGlassForClosedParticipant(UUID targetId, Map<String, Object> request) {
        int updated = jdbcTemplate.update("""
                update break_glass_capability_actions b
                set status = 'REVOKED', revoked_at = now(), revoked_by = ?, revoke_reason = ?
                from participants p
                where b.id = ? and b.participant_id = p.id and p.account_status = 'CLOSED' and b.status = 'ACTIVE'
                """, require(request, "actor"), require(request, "reason"), targetId);
        if (updated == 0) {
            throw new ConflictException("Break-glass capability action was not eligible for closed-account revoke repair");
        }
        return Map.of("repairExecuted", "REVOKE_BREAK_GLASS_FOR_CLOSED_PARTICIPANT", "rowsUpdated", updated);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void ensureExists(UUID id) {
        get(id);
    }

    private String require(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON payload", exception);
        }
    }
}
