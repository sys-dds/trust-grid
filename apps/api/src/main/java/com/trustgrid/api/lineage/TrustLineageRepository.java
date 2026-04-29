package com.trustgrid.api.lineage;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrustLineageRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TrustLineageRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    UUID rebuild(String type, Map<String, Object> request) {
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into lineage_rebuild_runs (id, rebuild_type, status, requested_by, reason)
                values (?, ?, 'RUNNING', ?, ?)
                """, runId, type, required(request, "requestedBy"), required(request, "reason"));
        int trust = 0;
        int ranking = 0;
        int policy = 0;
        if (type.equals("TRUST_SCORE_LINEAGE") || type.equals("FULL_LINEAGE")) {
            trust = rebuildTrustScoreLineage();
        }
        if (type.equals("RANKING_LINEAGE") || type.equals("FULL_LINEAGE")) {
            ranking = rebuildRankingLineage();
        }
        if (type.equals("POLICY_LINEAGE") || type.equals("FULL_LINEAGE")) {
            policy = rebuildPolicyLineage();
        }
        Map<String, Object> summary = Map.of("trustScoreEntries", trust, "rankingEntries", ranking,
                "policyEntries", policy, "duplicateSafe", true);
        jdbcTemplate.update("""
                update lineage_rebuild_runs set status = 'SUCCEEDED', completed_at = now(), summary_json = cast(? as jsonb)
                where id = ?
                """, json(summary), runId);
        return runId;
    }

    int rebuildTrustScoreLineage() {
        int count = 0;
        count += jdbcTemplate.update("""
                insert into trust_score_lineage_entries (
                    id, participant_id, reputation_snapshot_id, source_type, source_id, contribution_type,
                    contribution_value, policy_version, explanation
                )
                select gen_random_uuid(), reviewed_participant_id, null, 'REVIEW', id,
                       case when status like '%SUPPRESSED%' then 'SUPPRESSED' else 'POSITIVE' end,
                       case when status like '%SUPPRESSED%' then 0 else confidence_weight end,
                       'reputation_policy_v1', 'Review contribution to trust score'
                from marketplace_reviews
                on conflict (participant_id, source_type, source_id, policy_version) do update set
                    reputation_snapshot_id = excluded.reputation_snapshot_id,
                    contribution_type = excluded.contribution_type,
                    contribution_value = excluded.contribution_value,
                    explanation = excluded.explanation
                """);
        count += jdbcTemplate.update("""
                insert into trust_score_lineage_entries (
                    id, participant_id, reputation_snapshot_id, source_type, source_id, contribution_type,
                    contribution_value, policy_version, explanation
                )
                select gen_random_uuid(), opened_by_participant_id, null, 'DISPUTE', id, 'NEGATIVE', -25,
                       'dispute_policy_v1', 'Dispute contribution to trust score'
                from marketplace_disputes
                on conflict (participant_id, source_type, source_id, policy_version) do update set
                    reputation_snapshot_id = excluded.reputation_snapshot_id,
                    contribution_type = excluded.contribution_type,
                    contribution_value = excluded.contribution_value,
                    explanation = excluded.explanation
                """);
        count += jdbcTemplate.update("""
                insert into trust_score_lineage_entries (
                    id, participant_id, reputation_snapshot_id, source_type, source_id, contribution_type,
                    contribution_value, policy_version, explanation
                )
                select gen_random_uuid(), target_id, null, 'RISK_DECISION', id, 'NEGATIVE', -score,
                       policy_version, 'Risk decision contribution to trust score'
                from risk_decisions
                where target_type = 'PARTICIPANT'
                on conflict (participant_id, source_type, source_id, policy_version) do update set
                    reputation_snapshot_id = excluded.reputation_snapshot_id,
                    contribution_type = excluded.contribution_type,
                    contribution_value = excluded.contribution_value,
                    explanation = excluded.explanation
                """);
        count += jdbcTemplate.update("""
                insert into trust_score_lineage_entries (
                    id, participant_id, reputation_snapshot_id, source_type, source_id, contribution_type,
                    contribution_value, policy_version, explanation
                )
                select gen_random_uuid(), participant_id, null, 'PROFILE_QUALITY', participant_id, 'NEUTRAL', trust_score,
                       'reputation_policy_v1', 'Current profile quality and score baseline'
                from trust_profiles
                on conflict (participant_id, source_type, source_id, policy_version) do update set
                    reputation_snapshot_id = excluded.reputation_snapshot_id,
                    contribution_type = excluded.contribution_type,
                    contribution_value = excluded.contribution_value,
                    explanation = excluded.explanation
                """);
        return count;
    }

    int rebuildRankingLineage() {
        return jdbcTemplate.update("""
                insert into ranking_lineage_entries (
                    id, ranking_decision_id, listing_id, participant_id, policy_version, score, reasons_json, suppression_reason
                )
                select gen_random_uuid(), r.id, l.id, l.owner_participant_id, r.policy_version,
                       coalesce((r.scores_json ->> l.id::text)::int, 0),
                       coalesce(r.reasons_json -> l.id::text, '[]'::jsonb),
                       case when l.status <> 'LIVE' then l.status else null end
                from ranking_decision_logs r
                join marketplace_listings l on r.candidate_ids_json ? l.id::text
                on conflict (ranking_decision_id, listing_id, policy_version) do update set
                    participant_id = excluded.participant_id,
                    score = excluded.score,
                    reasons_json = excluded.reasons_json,
                    suppression_reason = excluded.suppression_reason
                """);
    }

    int rebuildPolicyLineage() {
        return jdbcTemplate.update("""
                insert into policy_lineage_entries (
                    id, decision_type, decision_id, policy_name, policy_version, matched_rules_json, exception_ids_json
                )
                select gen_random_uuid(), target_type, id, policy_name, policy_version, matched_rules_json, exception_ids_json
                from policy_decision_logs
                on conflict (decision_type, decision_id, policy_name, policy_version) do update set
                    matched_rules_json = excluded.matched_rules_json,
                    exception_ids_json = excluded.exception_ids_json
                """);
    }

    List<Map<String, Object>> trustLineage(UUID participantId) {
        return jdbcTemplate.queryForList("""
                select * from trust_score_lineage_entries where participant_id = ? order by created_at desc
                """, participantId);
    }

    Map<String, Object> trustExplanation(UUID participantId) {
        List<Map<String, Object>> lineage = trustLineage(participantId);
        int total = lineage.stream().mapToInt(row -> ((Number) row.get("contribution_value")).intValue()).sum();
        return Map.of("participantId", participantId, "lineageEntries", lineage, "contributionTotal", total,
                "explanation", "Trust score is explained by deterministic lineage entries from reviews, disputes, risk, moderation, restrictions, profile quality, and completion signals",
                "readOnly", true);
    }

    List<Map<String, Object>> rankingLineage(UUID listingId) {
        return jdbcTemplate.queryForList("select * from ranking_lineage_entries where listing_id = ? order by created_at desc", listingId);
    }

    List<Map<String, Object>> policyLineage(String policyName) {
        if (policyName == null || policyName.isBlank()) {
            return jdbcTemplate.queryForList("select * from policy_lineage_entries order by created_at desc limit 100");
        }
        return jdbcTemplate.queryForList("select * from policy_lineage_entries where policy_name = ? order by created_at desc limit 100", policyName);
    }

    List<Map<String, Object>> rebuildRuns() {
        return jdbcTemplate.queryForList("select * from lineage_rebuild_runs order by started_at desc");
    }

    private String required(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON payload", exception);
        }
    }
}
